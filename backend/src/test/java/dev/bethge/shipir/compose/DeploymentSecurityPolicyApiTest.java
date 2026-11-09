package dev.bethge.shipir.compose;

import dev.bethge.shipir.compose.file.ComposeGraphHasher;
import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The fake compose emits the YAML as JSON, so volumes use the long form that real {@code config --format json} normalizes to.
 * Non-ADMIN users cannot reach host root through Compose; ADMIN can; system runs follow the content author's role. */
class DeploymentSecurityPolicyApiTest extends DeploymentTestBase {

    private static final String PRIVILEGED = "services:\n  x:\n    image: alpine\n    privileged: true\n";
    private static final String ROOT_MOUNT = "services:\n  x:\n    image: alpine\n    volumes:\n      - {type: bind, source: /, target: /host}\n";

    @Autowired
    DeploymentRunService runs;

    private static String body(String name, String yaml) {
        return JsonMapper.builder().build().writeValueAsString(
                name == null ? Map.of("yaml", yaml) : Map.of("name", name, "yaml", yaml));
    }

    private UUID create(Login who, String name, String yaml) throws Exception {
        String res = mvc.perform(who.write(post(url()).contentType(APPLICATION_JSON).content(body(name, yaml))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(res, "$.id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "privileged: true", "cap_add: [SYS_ADMIN]", "pid: host", "ipc: host", "network_mode: host",
            "devices: ['/dev/sda:/dev/sda']", "security_opt: ['seccomp:unconfined']", "userns_mode: host",
            "volumes: [{type: bind, source: /, target: /host}]",
            "volumes: [{type: bind, source: /var/run/docker.sock, target: /var/run/docker.sock}]"})
    void operatorCannotCreateDeploymentWithHostLevelSetting(String setting) throws Exception {
        Login op = login("operator");
        String yaml = "services:\n  x:\n    image: alpine\n    " + setting + "\n";
        mvc.perform(op.write(post(url()).contentType(APPLICATION_JSON).content(body("evil", yaml))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
    }

    private static final String PROFILED_PRIVILEGED =
            "services:\n  web:\n    image: nginx\n  x:\n    image: alpine\n    profiles: [x]\n    privileged: true\n";

    @Test
    void privilegedServiceBehindAProfileIsStillAdminOnly() throws Exception {
        Login op = login("operator");
        mvc.perform(op.write(post(url()).contentType(APPLICATION_JSON).content(body("evil", PROFILED_PRIVILEGED))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));

        // admin stores and deploys it; an operator may not run it, neither as a whole nor service by service
        Login admin = login("admin");
        UUID id = create(admin, "ok", PROFILED_PRIVILEGED);
        mvc.perform(admin.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        await().atMost(10, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'DEPLOYMENT_DEPLOY_RESULT'", Integer.class)).isEqualTo(1));
        commands.clear();
        for (String path : List.of("/deploy", "/start", "/services/x/up", "/services/x/restart", "/pull-update")) {
            mvc.perform(op.write(post(url() + "/" + id + path)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        }
        assertThat(commands).isEmpty();
    }

    @Test
    void adminMayCreateAndDeployPrivilegedDeployment() throws Exception {
        Login admin = login("admin");
        UUID id = create(admin, "ok", PRIVILEGED);
        mvc.perform(admin.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/proc/self/environ", "../other/.env", "'${HOME}/.env'"})
    void operatorCannotReadFilesOutsideTheDeploymentDirectoryViaEnvFile(String path) throws Exception {
        String yaml = "services:\n  x:\n    image: alpine\n    env_file: " + path + "\n";
        mvc.perform(login("operator").write(post(url()).contentType(APPLICATION_JSON).content(body("leak", yaml))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
        assertThat(commands).doesNotContain(ComposeCommand.UP);
    }

    @Test
    void operatorCannotHideEnvFileBehindAYamlAlias() throws Exception {
        String yaml = "x-p: &p /proc/self/environ\nservices:\n  x:\n    image: alpine\n    env_file: *p\n";
        mvc.perform(login("operator").write(post(url()).contentType(APPLICATION_JSON).content(body("alias", yaml))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
    }

    @Test
    void operatorMayUseEnvFileInsideTheDeploymentDirectory() throws Exception {
        create(login("operator"), "local-env", "services:\n  x:\n    image: alpine\n    env_file: [app.env]\n");
    }

    @Test
    void adminIsExemptFromTheFilePathRestriction() throws Exception {
        create(login("admin"), "adminenv", "services:\n  x:\n    image: alpine\n    env_file: /etc/shipir.env\n");
    }

    @Test
    void operatorMayUseNamedVolumesAndPlainServices() throws Exception {
        create(login("operator"), "fine", "services:\n  x:\n    image: nginx\n    volumes:\n      - data:/data\nvolumes:\n  data: {}\n");
    }

    @Test
    void operatorCannotUpdateToHostLevelSettingAndStateStaysUntouched() throws Exception {
        Login op = login("operator");
        UUID id = create(op, "upd", YAML);
        mvc.perform(op.write(put(url() + "/" + id).contentType(APPLICATION_JSON).content(body(null, ROOT_MOUNT))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        assertThat(files.readCompose(hostId, "upd")).contains(YAML);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment_versions", Integer.class)).isEqualTo(1);
    }

    @Test
    void operatorCannotDeployOrRollbackVersionAuthoredByAdmin() throws Exception {
        UUID id = create(login("admin"), "adminmade", PRIVILEGED);
        UUID versionId = jdbc.queryForObject("SELECT id FROM deployment_versions", UUID.class);
        Login op = login("operator");
        mvc.perform(op.write(post(url() + "/" + id + "/deploy")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mvc.perform(op.write(post(url() + "/" + id + "/versions/" + versionId + "/rollback").contentType(APPLICATION_JSON)))
                .andExpect(status().isForbidden());
        assertThat(commands).doesNotContain(ComposeCommand.UP);
    }

    @Test
    void systemRunOfContentAuthoredByAdminIsAllowed() throws Exception {
        UUID id = create(login("admin"), "sysok", PRIVILEGED);
        jdbc.update("UPDATE deployment_versions SET deployed_at = now(), deploy_succeeded_at = now() WHERE deployment_id = ?", id);
        assertThat(runs.webhookTrigger(id).runId()).isNotBlank();
    }

    @Test
    void systemRunOfContentAuthoredByOperatorIsDenied() throws Exception {
        UUID id = create(login("admin"), "sysdeny", PRIVILEGED);
        jdbc.update("UPDATE deployment_versions SET deployed_at = now(), deploy_succeeded_at = now(), created_by = (SELECT id FROM users WHERE username = 'operator') "
                + "WHERE deployment_id = ?", id);
        assertThatThrownBy(() -> runs.webhookTrigger(id))
                .isInstanceOfSatisfying(DeploymentException.class, e -> assertThat(e.code()).isEqualTo("ADMIN_REQUIRED"));
        assertThat(commands).doesNotContain(ComposeCommand.PULL, ComposeCommand.UP);
    }

    private UUID deployedAdminPrivileged(String name) throws Exception {
        UUID id = create(login("admin"), name, PRIVILEGED);
        jdbc.update("UPDATE deployment_versions SET deployed_at = now(), deploy_succeeded_at = now() WHERE deployment_id = ?", id);
        return id;
    }

    /** An OPERATOR can add a file next to compose.yaml (config editor); the system run cannot vouch for it any more. */
    private void addAuxFile(String name) throws Exception {
        Files.writeString(files.deploymentDir(hostId, name).resolve("extra.yaml"), "services: {}\n");
    }

    @Test
    void systemRunIsRefusedWhenAnAuxFileAppearedNextToTheAdminAuthoredCompose() throws Exception {
        UUID id = deployedAdminPrivileged("sysaux");
        addAuxFile("sysaux");
        assertThatThrownBy(() -> runs.webhookTrigger(id))
                .isInstanceOfSatisfying(DeploymentException.class, e -> assertThat(e.code()).isEqualTo("ADMIN_REQUIRED"));
        assertThat(commands).doesNotContain(ComposeCommand.PULL, ComposeCommand.UP);
    }

    @Test
    void systemRunWithAuxFileStillRunsWhenTheComposeNeedsNoAdminPower() throws Exception {
        UUID id = create(login("admin"), "sysauxok", YAML);
        jdbc.update("UPDATE deployment_versions SET deployed_at = now(), deploy_succeeded_at = now() WHERE deployment_id = ?", id);
        addAuxFile("sysauxok");
        assertThat(runs.webhookTrigger(id).runId()).isNotBlank();
    }

    @Test
    void rollbackImagesIsRefusedForAViolatingCompose() throws Exception {
        UUID id = deployedAdminPrivileged("rbaux");
        addAuxFile("rbaux");
        commands.clear();
        assertThat(runs.rollbackImages(id, Map.of("x", "alpine@sha256:" + "a".repeat(64)))).isFalse();
        assertThat(commands).doesNotContain(ComposeCommand.UP);
    }

    @Test
    void rollbackImagesIsRefusedForOperatorAuthoredViolatingCompose() throws Exception {
        UUID id = create(login("admin"), "rbop", PRIVILEGED);
        jdbc.update("UPDATE deployment_versions SET deployed_at = now(), deploy_succeeded_at = now(), created_by = (SELECT id FROM users WHERE username = 'operator') "
                + "WHERE deployment_id = ?", id);
        commands.clear();
        assertThat(runs.rollbackImages(id, Map.of("x", "alpine@sha256:" + "a".repeat(64)))).isFalse();
        assertThat(commands).doesNotContain(ComposeCommand.UP);
    }

    @Test
    void rollbackImagesStillWorksForAnUnchangedAdminAuthoredCompose() throws Exception {
        UUID id = deployedAdminPrivileged("rbok");
        commands.clear();
        assertThat(runs.rollbackImages(id, Map.of("x", "alpine@sha256:" + "a".repeat(64)))).isTrue();
        assertThat(commands).containsExactly(ComposeCommand.UP);
    }

    /**
     * An ADMIN-authored version deployed successfully, then a newer OPERATOR-authored one was started but failed
     * (deployed_at set, deploy_succeeded_at null). System runs must follow the succeeded version's author.
     */
    private UUID adminSucceededThenOperatorFailed(String name) throws Exception {
        UUID id = deployedAdminPrivileged(name);
        jdbc.update("INSERT INTO deployment_versions (deployment_id, yaml_content, created_by, created_at, deployed_at) "
                + "SELECT ?, yaml_content, (SELECT id FROM users WHERE username = 'operator'), now() + interval '1 minute', "
                + "now() FROM deployment_versions WHERE deployment_id = ?", id, id);
        commands.clear();
        return id;
    }

    @Test
    void rollbackImagesGatesOnTheSucceededVersionNotALaterFailedOne() throws Exception {
        UUID id = adminSucceededThenOperatorFailed("rbfail");
        assertThat(runs.rollbackImages(id, Map.of("x", "alpine@sha256:" + "a".repeat(64)))).isTrue();
        assertThat(commands).containsExactly(ComposeCommand.UP);
    }

    @Test
    void webhookSystemRunGatesOnTheSucceededVersionNotALaterFailedOne() throws Exception {
        UUID id = adminSucceededThenOperatorFailed("whfail");
        assertThat(runs.webhookTrigger(id).runId()).isNotBlank();
    }

    @Test
    void systemRunOfGitDeploymentAuthoredByAdminUsesTheRestrictedProfile() throws Exception {
        UUID id = deployedAdminPrivileged("sysgit");
        jdbc.update("UPDATE deployments SET source_type = 'GIT', git_repo_url = 'https://example.com/r.git', "
                + "git_ref = 'main', git_compose_path = 'compose.yaml' WHERE id = ?", id);
        Files.createDirectories(files.repoDir(hostId, "sysgit"));
        Files.writeString(files.repoDir(hostId, "sysgit").resolve("compose.yaml"), PRIVILEGED);
        commands.clear();
        // the checkout is not covered by the ADMIN's approval, so even a directory without extra files is restricted
        assertThatThrownBy(() -> runs.autoPullUpdate(id))
                .isInstanceOfSatisfying(DeploymentException.class, e -> assertThat(e.code()).isEqualTo("ADMIN_REQUIRED"));
        assertThat(commands).doesNotContain(ComposeCommand.PULL, ComposeCommand.UP);
    }

    /** Deploys as ADMIN (records the graph hash) and waits for the worker to finish, so the lock is free again. */
    private UUID adminDeployed(String name, String yaml) throws Exception {
        Login admin = login("admin");
        UUID id = create(admin, name, yaml);
        mvc.perform(admin.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        await().atMost(10, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'DEPLOYMENT_DEPLOY_RESULT' AND target_id = ?",
                Integer.class, id.toString())).isEqualTo(1));
        commands.clear();
        return id;
    }

    @Test
    void adminDeployRecordsTheGraphHashOfTheDirectory() throws Exception {
        UUID id = adminDeployed("hashed", PRIVILEGED);
        String stored = jdbc.queryForObject("SELECT graph_hash FROM deployment_versions WHERE deployment_id = ? AND deployed_at IS NOT NULL", String.class, id);
        assertThat(stored).matches("[0-9a-f]{64}")
                .isEqualTo(ComposeGraphHasher.hash(files.deploymentDir(hostId, "hashed")).orElseThrow());
    }

    @Test
    void adminDeployThatMountsABackupDirectoryStoresNoGraphHash() throws Exception {
        String source = files.deploymentDir(hostId, "bkref") + "/.shipir-backups";
        UUID id = adminDeployed("bkref", "services:\n  x:\n    image: alpine\n    volumes:\n      - {type: bind, source: %s, target: /b}\n".formatted(source));
        assertThat(jdbc.queryForObject("SELECT graph_hash FROM deployment_versions WHERE deployment_id = ? AND deployed_at IS NOT NULL", String.class, id)).isNull();
    }

    /** shipr-kqbc.55: no hash because of unhashed references means restricted system runs, not the only-own-files fallback. */
    @Test
    void systemRunStaysRestrictedForAFileDeploymentWhoseComposeHasUnhashedReferences() throws Exception {
        String source = files.deploymentDir(hostId, "unhashedsys") + "/.shipir-backups";
        UUID id = adminDeployed("unhashedsys", "services:\n  x:\n    image: alpine\n    privileged: true\n    volumes:\n      - {type: bind, source: %s, target: /b}\n".formatted(source));
        assertThat(jdbc.queryForObject("SELECT graph_hash FROM deployment_versions WHERE deployment_id = ? AND deployed_at IS NOT NULL", String.class, id)).isNull();
        commands.clear();
        assertThatThrownBy(() -> runs.webhookTrigger(id))
                .isInstanceOfSatisfying(DeploymentException.class, e -> assertThat(e.code()).isEqualTo("ADMIN_REQUIRED"));
        assertThat(commands).doesNotContain(ComposeCommand.PULL, ComposeCommand.UP);
    }

    @Test
    void adminDeployWithEnvFileFromTheGitMetadataStoresNoGraphHash() throws Exception {
        UUID id = adminDeployed("gitref", "services:\n  x:\n    image: alpine\n    env_file: repo/.git/config\n");
        assertThat(jdbc.queryForObject("SELECT graph_hash FROM deployment_versions WHERE deployment_id = ? AND deployed_at IS NOT NULL", String.class, id)).isNull();
    }

    @Test
    void operatorDeployDoesNotRecordAGraphHash() throws Exception {
        Login op = login("operator");
        UUID id = create(op, "ophash", YAML);
        mvc.perform(op.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        await().atMost(10, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'DEPLOYMENT_DEPLOY_RESULT'", Integer.class)).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT graph_hash FROM deployment_versions WHERE deployment_id = ? AND deployed_at IS NOT NULL", String.class, id)).isNull();
    }

    @Test
    void systemRunStaysUnrestrictedForAnAuxFileThatTheAdminDeployedWith() throws Exception {
        Login admin = login("admin");
        UUID id = create(admin, "sysapproved", PRIVILEGED);
        addAuxFile("sysapproved"); // present when the ADMIN deploys, so it is part of what they approved
        mvc.perform(admin.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        await().atMost(10, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'DEPLOYMENT_DEPLOY_RESULT'", Integer.class)).isEqualTo(1));
        assertThat(runs.webhookTrigger(id).runId()).isNotBlank();
    }

    @Test
    void systemRunBecomesRestrictedWhenAnOperatorEditsAnApprovedAuxFile() throws Exception {
        Login admin = login("admin");
        UUID id = create(admin, "sysedit", PRIVILEGED);
        addAuxFile("sysedit");
        mvc.perform(admin.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        await().atMost(10, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'DEPLOYMENT_DEPLOY_RESULT'", Integer.class)).isEqualTo(1));
        commands.clear();
        // what the config editor does for an OPERATOR: change the content of an included file
        Files.writeString(files.deploymentDir(hostId, "sysedit").resolve("extra.yaml"), "services: {y: {image: evil}}\n");
        assertThatThrownBy(() -> runs.webhookTrigger(id))
                .isInstanceOfSatisfying(DeploymentException.class, e -> {
                    assertThat(e.code()).isEqualTo("ADMIN_REQUIRED");
                    assertThat(e.getMessage()).contains("ADMIN must deploy again");
                });
        assertThat(commands).doesNotContain(ComposeCommand.PULL, ComposeCommand.UP);
    }

    @Test
    void systemRunBecomesRestrictedWhenAnAuxFileIsAddedAfterTheAdminDeploy() throws Exception {
        UUID id = adminDeployed("sysnew", PRIVILEGED);
        assertThat(runs.webhookTrigger(id).runId()).isNotBlank(); // unchanged graph: still allowed
        await().atMost(10, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'DEPLOYMENT_PULL_UPDATE_RESULT'", Integer.class)).isEqualTo(1));
        commands.clear();
        addAuxFile("sysnew");
        assertThatThrownBy(() -> runs.webhookTrigger(id))
                .isInstanceOfSatisfying(DeploymentException.class, e -> assertThat(e.code()).isEqualTo("ADMIN_REQUIRED"));
        assertThat(commands).doesNotContain(ComposeCommand.PULL, ComposeCommand.UP);
    }

    private UUID approvedGitCheckout(String name) throws Exception {
        UUID id = deployedAdminPrivileged(name);
        jdbc.update("UPDATE deployments SET source_type = 'GIT', git_repo_url = 'https://example.com/r.git', "
                + "git_ref = 'main', git_compose_path = 'compose.yaml' WHERE id = ?", id);
        Files.createDirectories(files.repoDir(hostId, name));
        Files.writeString(files.repoDir(hostId, name).resolve("compose.yaml"), PRIVILEGED);
        jdbc.update("UPDATE deployment_versions SET graph_hash = ? WHERE deployment_id = ?",
                ComposeGraphHasher.hash(files.deploymentDir(hostId, name)).orElseThrow(), id);
        commands.clear();
        return id;
    }

    @Test
    void gitCheckoutApprovedByHashStaysUnrestrictedUntilItChanges() throws Exception {
        UUID id = approvedGitCheckout("gitok");
        Map<String, String> pins = Map.of("x", "alpine@sha256:" + "a".repeat(64));
        assertThat(runs.rollbackImages(id, pins)).isTrue();
        // a new commit lands in the checkout
        Files.writeString(files.repoDir(hostId, "gitok").resolve("compose.yaml"), PRIVILEGED + "# new commit\n");
        commands.clear();
        assertThat(runs.rollbackImages(id, pins)).isFalse();
        assertThat(commands).doesNotContain(ComposeCommand.UP);
    }

    @Test
    void gitCheckoutWithoutAHashStaysRestricted() throws Exception {
        UUID id = approvedGitCheckout("gitnohash");
        jdbc.update("UPDATE deployment_versions SET graph_hash = NULL WHERE deployment_id = ?", id);
        assertThat(runs.rollbackImages(id, Map.of("x", "alpine@sha256:" + "a".repeat(64)))).isFalse();
    }

    @Test
    void aHashOfAnotherTreeDoesNotApproveThisOne() throws Exception {
        UUID id = approvedGitCheckout("gitwrong");
        jdbc.update("UPDATE deployment_versions SET graph_hash = ? WHERE deployment_id = ?", "0".repeat(64), id);
        assertThat(runs.rollbackImages(id, Map.of("x", "alpine@sha256:" + "a".repeat(64)))).isFalse();
    }
}
