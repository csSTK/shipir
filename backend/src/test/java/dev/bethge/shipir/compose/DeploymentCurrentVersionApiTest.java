package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code current} in the version list means the latest successfully deployed version (shipr-qv7). */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class DeploymentCurrentVersionApiTest extends DeploymentTestBase {

    private static final String YAML_B = "services:\n  web:\n    image: nginx:alpine\n";
    private static final String ENV = "[{\"key\":\"MODE\",\"value\":\"prod\"}]";

    @MockitoBean
    ComposeOutputStreamService streams;

    private String create(Login l) throws Exception {
        when(streams.start(any())).thenReturn((Consumer<String>) line -> { });
        String body = mvc.perform(l.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, ENV)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** Starts a deploy or rollback and waits for its result entry, so the run has finished. */
    private void run(Login l, String path, String id) throws Exception {
        int before = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action LIKE '%\\_RESULT'", Integer.class);
        mvc.perform(l.write(post(url() + "/" + id + path))).andExpect(status().isAccepted());
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action LIKE '%\\_RESULT'", Integer.class)).isGreaterThan(before));
    }

    private void edit(Login l, String id, String yaml) throws Exception {
        mvc.perform(l.write(put(url() + "/" + id)).contentType(APPLICATION_JSON).content(updateJson(yaml, null)))
                .andExpect(status().isOk());
    }

    /** Ids of the versions flagged current (at most one is expected). */
    private List<String> currentIds(Login l, String id) throws Exception {
        String body = mvc.perform(get(url() + "/" + id + "/versions").session(l.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[?(@.current==true)].id");
    }

    private UUID latestVersion(String id, String where) {
        return jdbc.queryForObject("SELECT id FROM deployment_versions WHERE deployment_id = ?::uuid AND " + where
                + " ORDER BY created_at DESC LIMIT 1", UUID.class, id);
    }

    @Test
    void neverDeployedDeploymentHasNoCurrentVersion() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        assertThat(currentIds(admin, id)).isEmpty();
    }

    @Test
    void updateWithoutDeployKeepsCurrentOnTheDeployedVersionNotTheDraft() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        run(admin, "/deploy", id);
        UUID deployed = latestVersion(id, "deployed_at IS NOT NULL");

        edit(admin, id, YAML_B);

        UUID draft = latestVersion(id, "deployed_at IS NULL");
        assertThat(currentIds(admin, id)).containsExactly(deployed.toString()).doesNotContain(draft.toString());
    }

    @Test
    void successfulDeployMakesTheDeployedVersionCurrent() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        run(admin, "/deploy", id);
        UUID first = latestVersion(id, "deployed_at IS NOT NULL");
        assertThat(currentIds(admin, id)).containsExactly(first.toString());

        edit(admin, id, YAML_B);
        run(admin, "/deploy", id);
        UUID second = latestVersion(id, "deployed_at IS NOT NULL");
        assertThat(second).isNotEqualTo(first);
        assertThat(currentIds(admin, id)).containsExactly(second.toString());
    }

    @Test
    void failedDeployKeepsThePreviousSuccessfulVersionCurrent() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        run(admin, "/deploy", id);
        UUID good = latestVersion(id, "deployed_at IS NOT NULL");

        edit(admin, id, YAML_B);
        upFails = true;
        run(admin, "/deploy", id);

        assertThat(latestVersion(id, "deployed_at IS NOT NULL")).isNotEqualTo(good);
        assertThat(currentIds(admin, id)).containsExactly(good.toString());
    }

    @Test
    void failedFirstDeployLeavesNoCurrentVersion() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        upFails = true;
        run(admin, "/deploy", id);
        assertThat(currentIds(admin, id)).isEmpty();
    }

    @Test
    void successfulRollbackMakesTheRollbackVersionCurrent() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        run(admin, "/deploy", id);
        UUID first = latestVersion(id, "deployed_at IS NOT NULL");
        edit(admin, id, YAML_B);
        run(admin, "/deploy", id);

        run(admin, "/versions/" + first + "/rollback", id);

        UUID rollback = latestVersion(id, "source = 'ROLLBACK'");
        assertThat(currentIds(admin, id)).containsExactly(rollback.toString());
    }

    @Test
    void failedRollbackKeepsThePreviousSuccessfulVersionCurrent() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        run(admin, "/deploy", id);
        UUID first = latestVersion(id, "deployed_at IS NOT NULL");
        edit(admin, id, YAML_B);
        run(admin, "/deploy", id);
        UUID second = latestVersion(id, "deployed_at IS NOT NULL");

        upFails = true;
        run(admin, "/versions/" + first + "/rollback", id);

        assertThat(currentIds(admin, id)).containsExactly(second.toString());
    }
}
