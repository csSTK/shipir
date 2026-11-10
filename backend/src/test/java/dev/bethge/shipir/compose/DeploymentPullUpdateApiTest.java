package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import dev.bethge.shipir.common.stream.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST managed-deployments/{id}/pull-update}: pull then up on the deployed version, stubbed compose execution. */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class DeploymentPullUpdateApiTest extends DeploymentTestBase {

    @MockitoBean
    ComposeOutputStreamService streams;

    private final List<String> streamed = new CopyOnWriteArrayList<>();

    private String create(Login l) throws Exception {
        when(streams.start(any())).thenReturn((Consumer<String>) streamed::add);
        String body = mvc.perform(l.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void deployAndWait(Login l, String id) throws Exception {
        mvc.perform(l.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        awaitResults(1);
        commands.clear();
    }

    private void awaitResults(int n) {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action LIKE '%\\_RESULT'", Integer.class))
                        .isEqualTo(n));
    }

    private List<String> audit() {
        return jdbc.queryForList("SELECT action || ':' || outcome FROM audit_log ORDER BY occurred_at, id", String.class);
    }

    private int versionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM deployment_versions", Integer.class);
    }

    @Test
    void pullsThenUpsWithoutNewVersionStreamsAndAudits() throws Exception {
        Login op = login("operator");
        String id = create(op);
        deployAndWait(op, id);
        int versions = versionCount();

        String body = mvc.perform(op.write(post(url() + "/" + id + "/pull-update")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").exists())
                .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.runId");
        awaitResults(2);

        assertThat(commands).containsExactly(ComposeCommand.PULL, ComposeCommand.UP);
        assertThat(versionCount()).isEqualTo(versions);
        assertThat(streamed).contains("web Pulling", "Container web  Started");
        assertThat(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?::uuid", String.class, id))
                .isEqualTo("RUNNING");
        verify(streams, timeout(5000)).complete(runId, RunStatus.DONE);
        assertThat(audit()).contains("DEPLOYMENT_PULL_UPDATE:SUCCESS", "DEPLOYMENT_PULL_UPDATE_RESULT:SUCCESS");
    }

    @Test
    void pullFailureSkipsUpMarksErrorAndAuditsFailure() throws Exception {
        Login op = login("operator");
        String id = create(op);
        deployAndWait(op, id);
        pullFails = true;

        String body = mvc.perform(op.write(post(url() + "/" + id + "/pull-update")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.runId");
        awaitResults(2);

        assertThat(commands).containsExactly(ComposeCommand.PULL);
        assertThat(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?::uuid", String.class, id))
                .isEqualTo("ERROR");
        verify(streams, timeout(5000)).complete(runId, RunStatus.FAILED);
        assertThat(audit()).contains("DEPLOYMENT_PULL_UPDATE_RESULT:FAILURE");
    }

    @Test
    void upThrowingAfterSuccessfulPullIsFailureNotSuccess() throws Exception {
        Login op = login("operator");
        String id = create(op);
        deployAndWait(op, id);
        upThrows = true;

        String body = mvc.perform(op.write(post(url() + "/" + id + "/pull-update")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.runId");
        awaitResults(2);

        assertThat(commands).containsExactly(ComposeCommand.PULL, ComposeCommand.UP);
        assertThat(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?::uuid", String.class, id))
                .isEqualTo("ERROR");
        verify(streams, timeout(5000)).complete(runId, RunStatus.FAILED);
        assertThat(audit()).contains("DEPLOYMENT_PULL_UPDATE_RESULT:FAILURE")
                .doesNotContain("DEPLOYMENT_PULL_UPDATE_RESULT:SUCCESS");
    }

    @Test
    void neverDeployedIs400NotDeployedAndRunsNothing() throws Exception {
        Login op = login("operator");
        String id = create(op);
        commands.clear();

        mvc.perform(op.write(post(url() + "/" + id + "/pull-update")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_DEPLOYED"));
        assertThat(commands).isEmpty();
        assertThat(audit()).contains("DEPLOYMENT_PULL_UPDATE:FAILURE");
    }

    @Test
    void busyDeploymentIs409() throws Exception {
        Login op = login("operator");
        String id = create(op);
        deployAndWait(op, id);
        CountDownLatch gate = new CountDownLatch(1);
        upGate = gate;
        mvc.perform(op.write(post(url() + "/" + id + "/pull-update"))).andExpect(status().isAccepted());

        mvc.perform(op.write(post(url() + "/" + id + "/pull-update")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_IN_PROGRESS"));
        gate.countDown();
        awaitResults(2);
    }

    @Test
    void viewerIs403UnknownDeploymentIs404AndCsrfIsRequired() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        Login viewer = login("viewer");
        commands.clear();

        mvc.perform(viewer.write(post(url() + "/" + id + "/pull-update"))).andExpect(status().isForbidden());
        mvc.perform(admin.write(post(url() + "/" + UUID.randomUUID() + "/pull-update"))).andExpect(status().isNotFound());
        mvc.perform(post(url() + "/" + id + "/pull-update").session(admin.session())).andExpect(status().isForbidden());
        assertThat(commands).isEmpty();
    }
}
