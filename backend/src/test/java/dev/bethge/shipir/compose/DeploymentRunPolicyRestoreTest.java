package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.git.GitCheckout;
import dev.bethge.shipir.git.GitOperationException;
import dev.bethge.shipir.git.GitRepositoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A run denied by the security policy must leave the deployment directory as it was (shipr-kqbc.17): the compose file and
 * .env are written before the policy gate (the gate reads them from disk), so a denial has to put them back. A failed
 * restore after a git redeploy is audited instead of only logged.
 */
class DeploymentRunPolicyRestoreTest extends DeploymentTestBase {

    private static final String PRIVILEGED = "services:\n  x:\n    image: alpine\n    privileged: true\n";
    private static final String ON_DISK = "services:\n  web:\n    image: nginx:onDisk\n";
    private static final String ON_DISK_ENV = "MARKER=disk\n";

    @MockitoBean
    GitRepositoryService git;

    /** ADMIN-authored privileged deployment, marked as deployed, whose files on disk were then replaced (e.g. by the config editor). */
    private UUID adminPrivileged(String name) throws Exception {
        Login admin = login("admin");
        String res = mvc.perform(admin.write(post(url()).contentType(APPLICATION_JSON).content(
                        JsonMapper.builder().build().writeValueAsString(Map.of("name", name, "yaml", PRIVILEGED)))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(res, "$.id"));
        jdbc.update("UPDATE deployment_versions SET deployed_at = now(), deploy_succeeded_at = now() WHERE deployment_id = ?", id);
        files.write(hostId, name, ON_DISK, ON_DISK_ENV);
        commands.clear();
        return id;
    }

    private void assertDirectoryUntouched(String name) {
        assertThat(files.readCompose(hostId, name)).contains(ON_DISK);
        assertThat(files.readEnv(hostId, name)).contains(ON_DISK_ENV);
        assertThat(commands).doesNotContain(ComposeCommand.UP, ComposeCommand.PULL, ComposeCommand.RESTART);
    }

    @Test
    void deniedDeployLeavesTheDirectoryUnchanged() throws Exception {
        UUID id = adminPrivileged("dep");
        mvc.perform(login("operator").write(post(url() + "/" + id + "/deploy")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        assertDirectoryUntouched("dep");
    }

    @Test
    void deniedRollbackLeavesTheDirectoryUnchanged() throws Exception {
        UUID id = adminPrivileged("rb");
        UUID versionId = jdbc.queryForObject("SELECT id FROM deployment_versions WHERE deployment_id = ?", UUID.class, id);
        mvc.perform(login("operator").write(post(url() + "/" + id + "/versions/" + versionId + "/rollback")
                        .contentType(APPLICATION_JSON)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        assertDirectoryUntouched("rb");
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "pull-update", "services/x/restart", "services/x/pull", "services/x/up"})
    void deniedNonDeployRunsLeaveTheDirectoryUnchanged(String path) throws Exception {
        UUID id = adminPrivileged("steps");
        mvc.perform(login("operator").write(post(url() + "/" + id + "/" + path)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        assertDirectoryUntouched("steps");
    }

    @Test
    void deniedWebhookSystemRunLeavesTheDirectoryUnchanged() throws Exception {
        UUID id = adminPrivileged("hook");
        jdbc.update("UPDATE deployment_versions SET created_by = (SELECT id FROM users WHERE username = 'operator') "
                + "WHERE deployment_id = ?", id);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runsBean().webhookTrigger(id))
                .isInstanceOfSatisfying(DeploymentException.class, e -> assertThat(e.code()).isEqualTo("ADMIN_REQUIRED"));
        assertDirectoryUntouched("hook");
    }

    @org.springframework.beans.factory.annotation.Autowired
    DeploymentRunService runs;

    private DeploymentRunService runsBean() {
        return runs;
    }

    @Test
    void failedRestoreAfterDeniedGitRedeployIsAudited() throws Exception {
        UUID id = adminPrivileged("gitfail");
        String sha = "a".repeat(40);
        jdbc.update("UPDATE deployments SET source_type = 'GIT', git_repo_url = 'https://example.com/r.git', git_ref = 'main', "
                + "git_compose_path = 'compose.yaml' WHERE id = ?", id);
        jdbc.update("UPDATE deployment_versions SET commit_sha = ? WHERE deployment_id = ?", sha, id);
        java.nio.file.Files.createDirectories(files.repoDir(hostId, "gitfail"));
        java.nio.file.Files.writeString(files.repoDir(hostId, "gitfail").resolve("compose.yaml"), PRIVILEGED);
        when(git.sync(any(), any(), any(), any(), any())).thenReturn(new GitCheckout("b".repeat(40), PRIVILEGED));
        doThrow(new GitOperationException(org.springframework.http.HttpStatus.BAD_GATEWAY, "GIT_UNREACHABLE", "unreachable")).when(git).ensureCommit(any(), any(), any(), any(), any());

        mvc.perform(login("operator").write(post(url() + "/" + id + "/git/redeploy")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));

        List<String> entries = jdbc.queryForList("SELECT username || ':' || outcome FROM audit_log "
                + "WHERE action = 'DEPLOYMENT_RESTORE_FAILED' AND target_id = ?", String.class, id.toString());
        assertThat(entries).containsExactly("operator:FAILURE");
        assertThat(jdbc.queryForObject("SELECT details::text FROM audit_log WHERE action = 'DEPLOYMENT_RESTORE_FAILED'",
                String.class)).contains("GitOperationException");
    }

    @Test
    void diskStateToStringDoesNotLeakTheEnvContent() {
        String text = new DeploymentRunService.DiskState("services: {}", "SECRET_TOKEN=hunter2").toString();
        assertThat(text).doesNotContain("hunter2").doesNotContain("SECRET_TOKEN");
    }

    @Test
    void previouslyAbsentEnvFileStaysAbsentAfterADenial() throws Exception {
        UUID id = adminPrivileged("noenv");
        files.write(hostId, "noenv", ON_DISK, null);
        mvc.perform(login("operator").write(post(url() + "/" + id + "/deploy")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        assertThat(files.readCompose(hostId, "noenv")).contains(ON_DISK);
        assertThat(files.readEnv(hostId, "noenv")).isEmpty();
    }
}
