package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TOCTOU between the compose policy check and {@code up}: the files compose reads (include/extends/env_file targets) must
 * not change while a run holds the deployment lock, so the config editor refuses writes, deletes and restores then.
 */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class DeploymentConfigEditorLockTest extends DeploymentTestBase {

    private String filesUrl(String id) {
        return url() + "/" + id + "/files?path=extra.yaml";
    }

    @Test
    void configEditorIsRefusedWhileARunHoldsTheLockAndWorksAfterwards() throws Exception {
        Login op = login("operator");
        String body = mvc.perform(op.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        Path extra = files.deploymentDir(hostId, "web").resolve("extra.yaml");
        mvc.perform(op.write(put(filesUrl(id))).contentType(APPLICATION_JSON).content("{\"content\":\"a: 1\\n\"}"))
                .andExpect(status().isOk());
        mvc.perform(op.write(put(filesUrl(id))).contentType(APPLICATION_JSON).content("{\"content\":\"a: 1\\n# v2\\n\"}"))
                .andExpect(status().isOk());
        String backups = mvc.perform(get(url() + "/" + id + "/files/backups?path=extra.yaml").session(op.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String backupId = JsonPath.read(backups, "$[0].id");

        CountDownLatch gate = new CountDownLatch(1);
        upGate = gate;
        mvc.perform(op.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());

        // the run sits between its policy check and the end of up: the aux file must stay as the check saw it
        mvc.perform(op.write(put(filesUrl(id))).contentType(APPLICATION_JSON).content("{\"content\":\"b: 2\\n\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEPLOYMENT_IN_PROGRESS"));
        mvc.perform(op.write(delete(filesUrl(id))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEPLOYMENT_IN_PROGRESS"));
        mvc.perform(op.write(post(url() + "/" + id + "/files/restore?path=extra.yaml")).contentType(APPLICATION_JSON)
                        .content("{\"backupId\":\"" + backupId + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEPLOYMENT_IN_PROGRESS"));
        assertThat(Files.readString(extra)).isEqualTo("a: 1\n# v2\n");

        gate.countDown();
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForList("SELECT 1 FROM audit_log WHERE action LIKE '%\\_RESULT'")).isNotEmpty());
        mvc.perform(op.write(put(filesUrl(id))).contentType(APPLICATION_JSON).content("{\"content\":\"b: 2\\n\"}"))
                .andExpect(status().isOk());
        assertThat(Files.readString(extra)).isEqualTo("b: 2\n");
    }
}
