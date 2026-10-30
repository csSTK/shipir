package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every path that needs "the version that is deployed" uses deploy_succeeded_at, not deployed_at (a started but failed
 * deploy sets only the latter) (shipr-0qbh.34).
 */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class DeploymentVersionDeploySucceededTest extends DeploymentTestBase {

    private static final String YAML_B = "services:\n  web:\n    image: nginx:alpine\n";

    @MockitoBean
    ComposeOutputStreamService streams;

    private String create(Login l) throws Exception {
        when(streams.start(any())).thenReturn((Consumer<String>) line -> { });
        String body = mvc.perform(l.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void deploy(Login l, String id) throws Exception {
        int before = results();
        mvc.perform(l.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(results()).isGreaterThan(before));
    }

    private int results() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action LIKE '%\\_RESULT'", Integer.class);
    }

    private String failedFirstDeploy(Login l) throws Exception {
        String id = create(l);
        upFails = true;
        deploy(l, id);
        upFails = false;
        commands.clear();
        return id;
    }

    @Test
    void pullUpdateAfterOnlyAFailedDeployIsNotDeployed() throws Exception {
        Login op = login("operator");
        String id = failedFirstDeploy(op);

        mvc.perform(op.write(post(url() + "/" + id + "/pull-update")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_DEPLOYED"));
        assertThat(commands).isEmpty();
    }

    @Test
    void startAfterOnlyAFailedDeployIsNotDeployed() throws Exception {
        Login op = login("operator");
        String id = failedFirstDeploy(op);

        mvc.perform(op.write(post(url() + "/" + id + "/start")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_DEPLOYED"));
        assertThat(commands).isEmpty();
    }

    @Test
    void serviceActionAfterOnlyAFailedDeployIsNotDeployed() throws Exception {
        Login op = login("operator");
        String id = failedFirstDeploy(op);

        mvc.perform(op.write(post(url() + "/" + id + "/services/web/restart")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_DEPLOYED"));
        assertThat(commands).isEmpty();
    }

    @Test
    void pullUpdateAndStartRunTheLastSucceededVersionNotALaterFailedOne() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        deploy(admin, id);
        UUID good = jdbc.queryForObject("SELECT id FROM deployment_versions WHERE deploy_succeeded_at IS NOT NULL",
                UUID.class);
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON).content(updateJson(YAML_B, null)))
                .andExpect(status().isOk());
        upFails = true;
        deploy(admin, id);
        upFails = false;

        mvc.perform(admin.write(post(url() + "/" + id + "/pull-update")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.versionId").value(good.toString()));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(results()).isEqualTo(3));
        mvc.perform(admin.write(post(url() + "/" + id + "/start")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.versionId").value(good.toString()));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(results()).isEqualTo(4));
    }

    @Test
    void versionBaselineIgnoresAFailedDeploy() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        deploy(admin, id);
        UUID good = jdbc.queryForObject("SELECT id FROM deployment_versions WHERE deploy_succeeded_at IS NOT NULL",
                UUID.class);
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON).content(updateJson(YAML_B, null)))
                .andExpect(status().isOk());
        upFails = true;
        deploy(admin, id);
        UUID failed = jdbc.queryForObject("SELECT id FROM deployment_versions WHERE deployed_at IS NOT NULL "
                + "AND deploy_succeeded_at IS NULL", UUID.class);

        String body = mvc.perform(get(url() + "/" + id + "/versions/" + failed).session(admin.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.baselineVersionId")).isEqualTo(good.toString());
    }
}
