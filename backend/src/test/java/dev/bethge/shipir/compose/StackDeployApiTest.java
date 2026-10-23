package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import dev.bethge.shipir.streaming.DeploymentStatus;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class StackDeployApiTest extends StackTestBase {

    private static final String YAML_B = "services:\n  web:\n    image: nginx:alpine\n";
    private static final String ENV = "[{\"key\":\"MODE\",\"value\":\"prod\"}]";

    @MockitoBean
    ComposeOutputStreamService streams;

    private final List<String> streamed = new CopyOnWriteArrayList<>();

    private String create(Login l) throws Exception {
        when(streams.start(any())).thenReturn((Consumer<String>) streamed::add);
        String body = mvc.perform(l.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, ENV)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String deploy(Login l, String id) throws Exception {
        String body = mvc.perform(l.write(post(url() + "/" + id + "/deploy")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deploymentId").exists())
                .andExpect(jsonPath("$.versionId").exists())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.deploymentId");
    }

    private void awaitIdle(String id) {
        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            String s = jdbc.queryForObject("SELECT status FROM stacks WHERE id = ?::uuid", String.class, id);
            return !"STOPPED".equals(s) || !commands.contains(ComposeCommand.UP);
        });
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForList("SELECT 1 FROM audit_log WHERE action LIKE '%\\_RESULT'")).isNotEmpty());
    }

    private List<String> audit() {
        return jdbc.queryForList("SELECT action || ':' || outcome FROM audit_log ORDER BY occurred_at, id", String.class);
    }

    @Test
    void deployCreatesVersionRunsUpStreamsOutputAndAuditsTheResult() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        String deploymentId = deploy(admin, id);
        awaitIdle(id);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM stack_versions", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM stacks WHERE id = ?::uuid", String.class, id))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT last_deployed_at FROM stacks WHERE id = ?::uuid",
                java.sql.Timestamp.class, id)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM stacks WHERE id = ?::uuid", UUID.class, id))
                .isEqualTo(jdbc.queryForObject("SELECT id FROM stack_versions ORDER BY created_at DESC LIMIT 1",
                        UUID.class));
        assertThat(commands).contains(ComposeCommand.UP);
        assertThat(streamed).containsExactly("Container web  Starting", "Container web  Started");
        verify(streams).start(deploymentId);
        verify(streams, timeout(5000)).complete(deploymentId, DeploymentStatus.DONE);
        assertThat(audit()).contains("STACK_CREATE:SUCCESS", "STACK_DEPLOY:SUCCESS", "STACK_DEPLOY_RESULT:SUCCESS");
        assertThat(files.readEnv(hostId, "web").orElseThrow()).contains("MODE=prod");
        String details = jdbc.queryForObject("SELECT cast(details as text) FROM audit_log WHERE action = 'STACK_DEPLOY_RESULT'",
                String.class);
        assertThat(details).contains(deploymentId).doesNotContain("Started").doesNotContain("prod");
    }

    @Test
    void failedComposeMarksStackErrorAndAuditsFailure() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        upFails = true;
        String deploymentId = deploy(admin, id);
        awaitIdle(id);

        assertThat(jdbc.queryForObject("SELECT status FROM stacks WHERE id = ?::uuid", String.class, id))
                .isEqualTo("ERROR");
        assertThat(jdbc.queryForObject("SELECT last_deployed_at FROM stacks WHERE id = ?::uuid",
                java.sql.Timestamp.class, id)).isNull();
        verify(streams, timeout(5000)).complete(deploymentId, DeploymentStatus.FAILED);
        assertThat(audit()).contains("STACK_DEPLOY:SUCCESS", "STACK_DEPLOY_RESULT:FAILURE");
    }

    @Test
    void secondDeployWhileRunningIsRejectedWith409() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        CountDownLatch gate = new CountDownLatch(1);
        upGate = gate;
        deploy(admin, id);

        mvc.perform(admin.write(post(url() + "/" + id + "/deploy")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_IN_PROGRESS"));
        mvc.perform(admin.write(post(url() + "/" + id + "/stop")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_IN_PROGRESS"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stack_versions", Integer.class)).isEqualTo(2);
        assertThat(audit()).contains("STACK_DEPLOY:FAILURE");

        gate.countDown();
        awaitIdle(id);
        deploy(admin, id);
    }

    @Test
    void updateWhileDeployingIsRejectedWith409AndLeavesStateUntouched() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        CountDownLatch gate = new CountDownLatch(1);
        upGate = gate;
        deploy(admin, id);
        String composeBefore = files.readCompose(hostId, "web").orElseThrow();
        int versionsBefore = jdbc.queryForObject("SELECT count(*) FROM stack_versions", Integer.class);

        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(updateJson(YAML + "# edited\n", "[{\"key\":\"NEW\",\"value\":\"v\"}]")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_IN_PROGRESS"));

        assertThat(files.readCompose(hostId, "web")).contains(composeBefore);
        assertThat(files.readEnv(hostId, "web").orElseThrow()).contains("MODE").doesNotContain("NEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stack_versions", Integer.class))
                .isEqualTo(versionsBefore);

        gate.countDown();
        awaitIdle(id);
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(updateJson(YAML + "# edited\n", null)))
                .andExpect(status().isOk());
    }

    @Test
    void rollbackCreatesRollbackVersionWithOldContentAndDeploysIt() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        deploy(admin, id);
        awaitIdle(id);
        UUID firstVersion = jdbc.queryForObject(
                "SELECT id FROM stack_versions WHERE deployed_at IS NOT NULL", UUID.class);
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                .content(updateJson(YAML_B, "[{\"key\":\"MODE\",\"value\":\"changed\"}]"))).andExpect(status().isOk());
        deploy(admin, id);
        jdbc.update("DELETE FROM audit_log WHERE action LIKE '%\\_RESULT'");
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM stack_versions", Integer.class)).isEqualTo(4));
        commands.clear();
        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            try {
                mvc.perform(admin.write(post(url() + "/" + id + "/versions/" + firstVersion + "/rollback")))
                        .andExpect(status().isAccepted());
                return true;
            } catch (AssertionError e) {
                return false;
            }
        });
        awaitIdle(id);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM stack_versions", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT yaml_content FROM stack_versions WHERE source = 'ROLLBACK'",
                String.class)).isEqualTo(YAML);
        assertThat(files.readCompose(hostId, "web")).contains(YAML);
        assertThat(files.readEnv(hostId, "web").orElseThrow()).contains("MODE=prod").doesNotContain("changed");
        assertThat(commands).contains(ComposeCommand.UP);
        mvc.perform(get(url() + "/" + id).session(admin.session()))
                .andExpect(jsonPath("$.yaml").value(YAML));
        mvc.perform(get(url() + "/" + id + "/versions").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].source").value("ROLLBACK"))
                .andExpect(jsonPath("$[0].deployed").value(true))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].createdBy").value("admin"))
                .andExpect(jsonPath("$[0].yaml").doesNotExist());
        assertThat(audit()).contains("STACK_ROLLBACK:SUCCESS", "STACK_ROLLBACK_RESULT:SUCCESS");
    }

    @Test
    void editAfterDeployKeepsDeployedVersionImmutableAndStartsNewDraft() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        deploy(admin, id);
        awaitIdle(id);
        UUID deployed = jdbc.queryForObject("SELECT id FROM stack_versions WHERE deployed_at IS NOT NULL", UUID.class);
        byte[] envBefore = jdbc.queryForObject("SELECT env_ciphertext FROM stack_versions WHERE id = ?", byte[].class,
                deployed);

        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                .content(updateJson(YAML_B, "[{\"key\":\"MODE\",\"value\":\"changed\"}]"))).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT yaml_content FROM stack_versions WHERE id = ?", String.class, deployed))
                .isEqualTo(YAML);
        assertThat(jdbc.queryForObject("SELECT env_ciphertext FROM stack_versions WHERE id = ?", byte[].class, deployed))
                .isEqualTo(envBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stack_versions", Integer.class)).isEqualTo(3);
        mvc.perform(get(url() + "/" + id).session(admin.session())).andExpect(jsonPath("$.yaml").value(YAML_B));
        mvc.perform(get(url() + "/" + id + "/versions").session(admin.session()))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].deployed").value(false))
                .andExpect(jsonPath("$[0].source").value("MANUAL"))
                .andExpect(jsonPath("$[1].deployed").value(true));

        // a second edit changes the draft in place
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                .content(updateJson(YAML_B + "# again\n", null))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stack_versions", Integer.class)).isEqualTo(3);

        mvc.perform(admin.write(post(url() + "/" + id + "/versions/" + deployed + "/rollback")))
                .andExpect(status().isAccepted());
        awaitIdle(id);
        assertThat(files.readCompose(hostId, "web")).contains(YAML);
        assertThat(files.readEnv(hostId, "web").orElseThrow()).contains("MODE=prod");
    }

    @Test
    void editOfUndeployedDraftStaysInPlace() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        mvc.perform(admin.write(put(url() + "/" + id)).contentType(APPLICATION_JSON)
                .content(updateJson(YAML_B, null))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stack_versions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT yaml_content FROM stack_versions", String.class)).isEqualTo(YAML_B);
    }

    @Test
    void rollbackToUnknownVersionOrVersionOfAnotherStackIs404() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        mvc.perform(admin.write(post(url() + "/" + id + "/versions/" + UUID.randomUUID() + "/rollback")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
        String body = mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                .content(createJson("other", YAML, null))).andReturn().getResponse().getContentAsString();
        String otherId = JsonPath.read(body, "$.id");
        UUID foreign = jdbc.queryForObject("SELECT id FROM stack_versions WHERE stack_id = ?::uuid", UUID.class, otherId);
        mvc.perform(admin.write(post(url() + "/" + id + "/versions/" + foreign + "/rollback")))
                .andExpect(status().isNotFound());
        // the failed attempt must not leave the stack locked
        deploy(admin, id);
    }

    @Test
    void stopRunsDownAndSetsStopped() throws Exception {
        Login operator = login("operator");
        String id = create(login("admin"));
        deploy(operator, id);
        awaitIdle(id);
        mvc.perform(operator.write(post(url() + "/" + id + "/stop")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));
        assertThat(commands).contains(ComposeCommand.DOWN);
        assertThat(audit()).contains("STACK_STOP:SUCCESS");
    }

    @Test
    void failedStopIs502AuditedAsFailureAndKeepsStatus() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        deploy(admin, id);
        awaitIdle(id);
        downFails = true;
        mvc.perform(admin.write(post(url() + "/" + id + "/stop")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("COMPOSE_DOWN_FAILED"));
        assertThat(jdbc.queryForObject("SELECT status FROM stacks WHERE id = ?::uuid", String.class, id))
                .isEqualTo("RUNNING");
        assertThat(audit()).contains("STACK_STOP:FAILURE");
        downFails = false;
        mvc.perform(admin.write(post(url() + "/" + id + "/stop"))).andExpect(status().isOk());
    }

    @Test
    void viewerCannotDeployStopOrRollbackButCanReadVersions() throws Exception {
        String id = create(login("admin"));
        UUID version = jdbc.queryForObject("SELECT id FROM stack_versions", UUID.class);
        Login viewer = login("viewer");
        mvc.perform(viewer.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isForbidden());
        mvc.perform(viewer.write(post(url() + "/" + id + "/stop"))).andExpect(status().isForbidden());
        mvc.perform(viewer.write(post(url() + "/" + id + "/versions/" + version + "/rollback")))
                .andExpect(status().isForbidden());
        mvc.perform(get(url() + "/" + id + "/versions").session(viewer.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        assertThat(commands).doesNotContain(ComposeCommand.UP);
    }

    @Test
    void deployWithoutCsrfTokenIsRejected() throws Exception {
        String id = create(login("admin"));
        mvc.perform(post(url() + "/" + id + "/deploy").session(login("admin").session()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteIsBlockedWhileDeploying() throws Exception {
        Login admin = login("admin");
        String id = create(admin);
        CountDownLatch gate = new CountDownLatch(1);
        upGate = gate;
        deploy(admin, id);
        mvc.perform(admin.write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete(url() + "/" + id)))
                .andExpect(status().isConflict());
        gate.countDown();
        awaitIdle(id);
    }
}
