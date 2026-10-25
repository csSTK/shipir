package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.compose.DeploymentService;
import dev.bethge.shipir.compose.dto.EnvEntry;
import dev.bethge.shipir.compose.dto.DeploymentCreateRequest;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.docker.client.DockerSystemService;
import dev.bethge.shipir.git.GitCheckout;
import dev.bethge.shipir.git.GitRepositoryService;
import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Every write tool end to end against real Postgres and the real services: only {@code docker compose} and the
 * Docker ping are stubbed. Confirmed calls must be audited as the acting user with {@code via: AI}.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class WriteToolsTest {

    static final Path BASE_DIR = baseDir();
    static final String ENV_SECRET = "env-value-SUPERSECRET-123";
    static final String YAML = "services:\n  web:\n    image: nginx\n";
    static final UUID SESSION = UUID.randomUUID();

    private static Path baseDir() {
        try {
            return Files.createTempDirectory("shipir-ai-write-tools-test-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void stacksDir(DynamicPropertyRegistry registry) {
        registry.add("shipir.stacks.base-dir", BASE_DIR::toString);
    }

    @Autowired
    AiToolRegistry registry;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    DeploymentService deployments;
    @Autowired
    ObjectMapper mapper;
    @MockitoBean
    ComposeExecutionService execution;
    @MockitoBean
    DockerSystemService docker;
    @MockitoBean
    GitRepositoryService git;

    UUID hostId;
    UUID deploymentId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM notification_webhooks");
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM git_credentials");
        jdbc.update("DELETE FROM docker_hosts");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode("irrelevant-Passw0rd-for-tools!"), Instant.now());
            users.save(u);
        }
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Local host', 'LOCAL', id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'Local host'", UUID.class);
        grant("operator", hostId, "OPERATE");
        grant("viewer", hostId, "VIEW");
        ComposeProcessResult ok = new ComposeProcessResult(0, false, List.of());
        when(execution.execute(any(), any(), any(), any(), any())).thenReturn(ok);
        when(execution.execute(any(), any(), any(), any(), any(), any())).thenReturn(ok);
        when(git.sync(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Path file = ((Path) inv.getArgument(0)).resolve((String) inv.getArgument(3));
            Files.createDirectories(file.getParent());
            Files.writeString(file, YAML);
            return new GitCheckout("abc123", YAML);
        });
        when(git.credentialExists(any())).thenReturn(false);
        when(docker.ping(any())).thenReturn(new ConnectionTestResult(true, "27.0", null, null));
        deploymentId = dev.bethge.shipir.common.security.SystemScope.call(() -> deployments.create(hostId,
                new DeploymentCreateRequest("web", YAML, List.of(new EnvEntry("API_TOKEN", ENV_SECRET))), "admin").id());
        jdbc.update("DELETE FROM audit_log");
    }

    private void grant(String username, UUID host, String level) {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) SELECT id, ?, ? FROM users WHERE username = ?",
                host, level, username);
    }

    private ToolContext ctx(Role role) {
        String name = role.name().toLowerCase();
        return new ToolContext(users.findByUsername(name).orElseThrow().getId(), name, role);
    }

    /** Proposes, then confirms like the orchestrator does after the user clicked yes. */
    private ToolResult confirmed(String tool, String args, Role role) {
        ProposalResult p = registry.propose(tool, args, ctx(role), SESSION);
        assertThat(p.accepted()).as(tool + ": " + p.error()).isTrue();
        return registry.executeConfirmed(p.action().token(), ctx(role));
    }

    private static String stack(UUID host, UUID stack) {
        return "{\"hostId\":\"" + host + "\",\"deploymentId\":\"" + stack + "\"}";
    }

    private Map<String, Object> auditEntry(String action) {
        return jdbc.queryForMap("SELECT a.username, a.user_id, a.outcome, a.details::text AS details FROM audit_log a "
                + "WHERE a.action = ?", action);
    }

    private void assertAuditedViaAi(String action, Role role) {
        Map<String, Object> row = auditEntry(action);
        assertThat(row.get("username")).isEqualTo(role.name().toLowerCase());
        assertThat(row.get("user_id")).isNotNull();
        assertThat(row.get("outcome")).isEqualTo("SUCCESS");
        JsonNode details = mapper.readTree((String) row.get("details"));
        assertThat(details.get("via").asString()).isEqualTo("AI");
        assertThat(details.get("chatSessionId").asString()).isEqualTo(SESSION.toString());
    }

    private void awaitAudit(String action) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Integer.class, action);
            if (n != null && n > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no audit entry " + action);
    }

    @Test
    void toolSetPerRole() {
        assertThat(registry.toolsFor(Role.VIEWER)).noneMatch(AiTool::write);
        assertThat(registry.toolsFor(Role.OPERATOR)).filteredOn(AiTool::write).extracting(AiTool::name)
                .containsExactlyInAnyOrder("run_deployment", "stop_deployment", "rollback_deployment", "git_redeploy_deployment",
                        "create_deployment", "update_deployment", "delete_deployment", "set_auto_update", "mark_notifications_read");
        assertThat(registry.toolsFor(Role.ADMIN)).filteredOn(AiTool::write).extracting(AiTool::name)
                .containsExactlyInAnyOrder("run_deployment", "stop_deployment", "rollback_deployment", "git_redeploy_deployment",
                        "create_deployment", "update_deployment", "delete_deployment", "set_auto_update", "mark_notifications_read",
                        "create_host", "rename_host", "delete_host", "set_webhook_enabled", "delete_webhook",
                        "delete_git_credential");
    }

    @Test
    void writeToolsNeverRunWithoutConfirmation() {
        ToolResult result = registry.execute("delete_deployment", stack(hostId, deploymentId), ctx(Role.ADMIN));

        assertThat(result.content()).contains("CONFIRMATION_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isEqualTo(1);
    }

    @Test
    void unknownArgumentsAreRejectedWhenProposingAgainstTheRealSchema() {
        ProposalResult p = registry.propose("delete_deployment",
                "{\"hostId\":\"" + hostId + "\",\"deploymentId\":\"" + deploymentId + "\",\"bogus\":1}", ctx(Role.ADMIN), SESSION);

        assertThat(p.accepted()).isFalse();
        assertThat(p.error().content()).contains("INVALID_ARGUMENTS", "unknown argument");
    }

    @Test
    void operatorAndViewerCannotUseAdminOrWriteTools() {
        UUID other = UUID.randomUUID();
        for (String tool : List.of("delete_host", "rename_host", "create_host", "delete_webhook", "delete_git_credential")) {
            ProposalResult p = registry.propose(tool, "{\"hostId\":\"" + other + "\"}", ctx(Role.OPERATOR), SESSION);
            assertThat(p.accepted()).as(tool).isFalse();
            assertThat(p.error().content()).as(tool).contains("UNKNOWN_TOOL");
        }
        assertThat(registry.propose("run_deployment", stack(hostId, deploymentId), ctx(Role.VIEWER), SESSION).accepted()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM docker_hosts", Integer.class)).isEqualTo(1);
    }

    @Test
    void createStackStoresYamlAndEncryptedEnvAndIsAuditedViaAi() {
        String args = mapper.writeValueAsString(Map.of("hostId", hostId.toString(), "name", "api", "yaml", YAML,
                "env", "DB_PASSWORD=" + ENV_SECRET + "\n# comment\nMODE=prod"));

        ToolResult result = confirmed("create_deployment", args, Role.OPERATOR);

        assertThat(result.error()).as(result.content()).isFalse();
        JsonNode json = mapper.readTree(result.content());
        assertThat(json.get("name").asString()).isEqualTo("api");
        assertThat(json.get("envKeys")).extracting(JsonNode::asString).containsExactly("DB_PASSWORD", "MODE");
        assertThat(result.content()).doesNotContain(ENV_SECRET);
        assertAuditedViaAi("DEPLOYMENT_CREATE", Role.OPERATOR);
        assertThat(auditEntry("DEPLOYMENT_CREATE").get("details").toString()).doesNotContain(ENV_SECRET);
    }

    @Test
    void createDeploymentFromGitStoresTheSourceAndIsAuditedViaAi() {
        jdbc.update("INSERT INTO git_credentials (name, username, token_ciphertext, token_hint, created_by) "
                + "SELECT 'cred', 'bob', ?, '...1234', id FROM users WHERE username = 'admin'", new byte[]{1, 2, 3});
        UUID credentialId = jdbc.queryForObject("SELECT id FROM git_credentials", UUID.class);
        when(git.credentialExists(credentialId)).thenReturn(true);
        String args = mapper.writeValueAsString(Map.of("hostId", hostId.toString(), "name", "app", "source", "GIT",
                "repoUrl", "https://example.com/r.git", "ref", "main", "composePath", "docker-compose.yml",
                "credentialId", credentialId.toString()));

        ToolResult result = confirmed("create_deployment", args, Role.OPERATOR);

        assertThat(result.error()).as(result.content()).isFalse();
        Map<String, Object> row = jdbc.queryForMap("SELECT source_type, git_repo_url, git_ref, git_compose_path, "
                + "git_credential_id FROM deployments WHERE name = 'app'");
        assertThat(row.get("source_type")).isEqualTo("GIT");
        assertThat(row.get("git_repo_url")).isEqualTo("https://example.com/r.git");
        assertThat(row.get("git_ref")).isEqualTo("main");
        assertThat(row.get("git_compose_path")).isEqualTo("docker-compose.yml");
        assertThat(row.get("git_credential_id")).isEqualTo(credentialId);
        assertAuditedViaAi("DEPLOYMENT_CREATE", Role.OPERATOR);
    }

    @Test
    void createDeploymentFromGitWithoutRequiredFieldsIsAnErrorResult() {
        String noRef = mapper.writeValueAsString(Map.of("hostId", hostId.toString(), "name", "app", "source", "GIT",
                "repoUrl", "https://example.com/r.git", "composePath", "compose.yaml"));
        ToolResult result = confirmed("create_deployment", noRef, Role.OPERATOR);
        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("ref");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments WHERE name = 'app'", Integer.class)).isZero();
    }

    @Test
    void createDeploymentWithFileSourceStillNeedsYaml() {
        String args = mapper.writeValueAsString(Map.of("hostId", hostId.toString(), "name", "app"));
        ToolResult result = confirmed("create_deployment", args, Role.OPERATOR);
        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("VALIDATION_ERROR").contains("yaml");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments WHERE name = 'app'", Integer.class)).isZero();
    }

    @Test
    void createDeploymentFromGitWithUnknownCredentialIsAnErrorResult() {
        UUID unknown = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new dev.bethge.shipir.git.GitOperationException(
                org.springframework.http.HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "Git credential not found"))
                .when(git).requireCredentialForUrl(org.mockito.ArgumentMatchers.eq(unknown), any());
        String args = mapper.writeValueAsString(Map.of("hostId", hostId.toString(), "name", "app", "source", "GIT",
                "repoUrl", "https://example.com/r.git", "ref", "main", "composePath", "compose.yaml",
                "credentialId", unknown.toString()));
        ToolResult result = confirmed("create_deployment", args, Role.OPERATOR);
        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("CREDENTIAL_NOT_FOUND");
    }

    @Test
    void createStackReportsServiceErrorsAsResults() {
        String args = mapper.writeValueAsString(Map.of("hostId", hostId.toString(), "name", "web", "yaml", YAML));

        ToolResult result = confirmed("create_deployment", args, Role.OPERATOR);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("DEPLOYMENT_NAME_TAKEN");
        assertThat(auditEntry("DEPLOYMENT_CREATE").get("outcome")).isEqualTo("FAILURE");
    }

    @Test
    void writeToolOnANonGrantedTargetIsAnErrorResult() {
        jdbc.update("DELETE FROM permissions");

        ToolResult create = confirmed("create_deployment",
                mapper.writeValueAsString(Map.of("hostId", hostId.toString(), "name", "other", "yaml", YAML)), Role.OPERATOR);
        ToolResult run = confirmed("run_deployment", stack(hostId, deploymentId), Role.OPERATOR);

        assertThat(create.error()).isTrue();
        assertThat(run.error()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isEqualTo(1);
    }

    @Test
    void viewGrantDoesNotAllowWriteTools() {
        jdbc.update("DELETE FROM permissions");
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) SELECT id, ?, 'VIEW' FROM users WHERE username = 'operator'",
                hostId);

        ToolResult result = confirmed("run_deployment", stack(hostId, deploymentId), Role.OPERATOR);

        assertThat(result.error()).isTrue();
    }

    @Test
    void updateStackSavesADraft() {
        String yaml = "services:\n  web:\n    image: nginx:1.27\n";
        String args = mapper.writeValueAsString(Map.of("hostId", hostId.toString(), "deploymentId", deploymentId.toString(), "yaml", yaml));

        ToolResult result = confirmed("update_deployment", args, Role.OPERATOR);

        assertThat(result.error()).as(result.content()).isFalse();
        assertThat(mapper.readTree(result.content()).get("yaml").asString()).contains("nginx:1.27");
        assertThat(mapper.readTree(result.content()).get("envKeys")).extracting(JsonNode::asString).containsExactly("API_TOKEN");
        assertAuditedViaAi("DEPLOYMENT_UPDATE", Role.OPERATOR);
    }

    @Test
    void deployStopRollbackAndDeleteRunThroughTheServices() throws InterruptedException {
        ToolResult deploy = confirmed("run_deployment", stack(hostId, deploymentId), Role.OPERATOR);
        assertThat(deploy.error()).as(deploy.content()).isFalse();
        assertThat(mapper.readTree(deploy.content()).get("runId").asString()).isNotBlank();
        assertAuditedViaAi("DEPLOYMENT_DEPLOY", Role.OPERATOR);
        awaitAudit("DEPLOYMENT_DEPLOY_RESULT");

        ToolResult stop = confirmed("stop_deployment", stack(hostId, deploymentId), Role.OPERATOR);
        assertThat(stop.error()).as(stop.content()).isFalse();
        assertAuditedViaAi("DEPLOYMENT_STOP", Role.OPERATOR);

        UUID version = jdbc.queryForObject("SELECT id FROM deployment_versions WHERE deployment_id = ? ORDER BY created_at LIMIT 1",
                UUID.class, deploymentId);
        ToolResult rollback = confirmed("rollback_deployment", "{\"hostId\":\"" + hostId + "\",\"deploymentId\":\"" + deploymentId
                + "\",\"versionId\":\"" + version + "\"}", Role.OPERATOR);
        assertThat(rollback.error()).as(rollback.content()).isFalse();
        assertAuditedViaAi("DEPLOYMENT_ROLLBACK", Role.OPERATOR);
        awaitAudit("DEPLOYMENT_ROLLBACK_RESULT");

        ToolResult delete = confirmed("delete_deployment", stack(hostId, deploymentId), Role.OPERATOR);
        assertThat(delete.error()).as(delete.content()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
        assertAuditedViaAi("DEPLOYMENT_DELETE", Role.OPERATOR);
    }

    @Test
    void gitToolsRejectFileStacksWithTheServiceCode() {
        ToolResult redeploy = confirmed("git_redeploy_deployment", stack(hostId, deploymentId), Role.OPERATOR);
        assertThat(redeploy.error()).isTrue();
        assertThat(redeploy.content()).contains("NOT_A_GIT_DEPLOYMENT");

        ToolResult auto = confirmed("set_auto_update", "{\"hostId\":\"" + hostId + "\",\"deploymentId\":\"" + deploymentId
                + "\",\"enabled\":true,\"intervalSeconds\":120}", Role.OPERATOR);
        assertThat(auto.error()).isTrue();
        assertThat(auto.content()).contains("NOT_A_GIT_DEPLOYMENT");
    }

    @Test
    void setAutoUpdateConfiguresAGitStack() {
        jdbc.update("UPDATE deployments SET source_type = 'GIT', git_repo_url = 'https://example.com/r.git', git_ref = 'main', "
                + "git_compose_path = 'compose.yaml' WHERE id = ?", deploymentId);

        ToolResult result = confirmed("set_auto_update", "{\"hostId\":\"" + hostId + "\",\"deploymentId\":\"" + deploymentId
                + "\",\"enabled\":true,\"intervalSeconds\":120}", Role.OPERATOR);

        assertThat(result.error()).as(result.content()).isFalse();
        assertThat(jdbc.queryForObject("SELECT auto_update_interval_seconds FROM deployments WHERE id = ?", Integer.class, deploymentId))
                .isEqualTo(120);
        assertAuditedViaAi("DEPLOYMENT_AUTO_UPDATE_CONFIG", Role.OPERATOR);
    }

    @Test
    void markNotificationsReadOnlyTouchesTheCallersOwn() {
        UUID operator = ctx(Role.OPERATOR).userId();
        UUID admin = ctx(Role.ADMIN).userId();
        jdbc.update("INSERT INTO notifications (user_id, type, host_id, host_name, message) VALUES (?, 'HOST_DOWN', ?, 'h', 'one')", operator, hostId);
        jdbc.update("INSERT INTO notifications (user_id, type, host_id, host_name, message) VALUES (?, 'HOST_DOWN', ?, 'h', 'two')", operator, hostId);
        jdbc.update("INSERT INTO notifications (user_id, type, host_id, host_name, message) VALUES (?, 'HOST_DOWN', ?, 'h', 'admins')", admin, hostId);
        UUID one = jdbc.queryForObject("SELECT id FROM notifications WHERE message = 'one'", UUID.class);
        UUID admins = jdbc.queryForObject("SELECT id FROM notifications WHERE message = 'admins'", UUID.class);

        assertThat(confirmed("mark_notifications_read", "{\"notificationId\":\"" + one + "\"}", Role.OPERATOR).error()).isFalse();
        assertThat(unread(operator)).isEqualTo(1);
        assertThat(confirmed("mark_notifications_read", "{\"notificationId\":\"" + admins + "\"}", Role.OPERATOR).content())
                .contains("INVALID_ARGUMENTS");
        assertThat(unread(admin)).isEqualTo(1);

        assertThat(confirmed("mark_notifications_read", "{}", Role.OPERATOR).error()).isFalse();
        assertThat(unread(operator)).isZero();
        assertThat(unread(admin)).isEqualTo(1);
    }

    private int unread(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND read_at IS NULL", Integer.class, user);
    }

    @Test
    void hostToolsCreateRenameAndDeleteLocalEnvironments() {
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM docker_hosts");

        ToolResult created = confirmed("create_host", "{\"name\":\"Server\"}", Role.ADMIN);
        assertThat(created.error()).as(created.content()).isFalse();
        UUID id = UUID.fromString(mapper.readTree(created.content()).get("id").asString());
        assertAuditedViaAi("HOST_CREATE", Role.ADMIN);

        ToolResult renamed = confirmed("rename_host", "{\"hostId\":\"" + id + "\",\"name\":\"Prod\"}", Role.ADMIN);
        assertThat(renamed.error()).as(renamed.content()).isFalse();
        assertThat(jdbc.queryForObject("SELECT name FROM docker_hosts WHERE id = ?", String.class, id)).isEqualTo("Prod");
        assertAuditedViaAi("HOST_RENAME", Role.ADMIN);

        ToolResult deleted = confirmed("delete_host", "{\"hostId\":\"" + id + "\"}", Role.ADMIN);
        assertThat(deleted.error()).as(deleted.content()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM docker_hosts", Integer.class)).isZero();
        assertAuditedViaAi("HOST_DELETE", Role.ADMIN);
    }

    @Test
    void webhookToolsToggleAndDeleteWithoutExposingTheUrl() {
        jdbc.update("INSERT INTO notification_webhooks (name, url_encrypted, url_hint, event_types, created_by) "
                        + "SELECT 'hook', ?, 'https://hooks.example', ARRAY['HOST_DOWN'], id FROM users WHERE username = 'admin'",
                "SECRET-URL".getBytes(StandardCharsets.UTF_8));
        UUID id = jdbc.queryForObject("SELECT id FROM notification_webhooks", UUID.class);

        ToolResult off = confirmed("set_webhook_enabled", "{\"webhookId\":\"" + id + "\",\"enabled\":false}", Role.ADMIN);
        assertThat(off.error()).as(off.content()).isFalse();
        assertThat(jdbc.queryForObject("SELECT enabled FROM notification_webhooks", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT url_encrypted FROM notification_webhooks", byte[].class))
                .isEqualTo("SECRET-URL".getBytes(StandardCharsets.UTF_8));
        assertThat(off.content()).doesNotContain("SECRET-URL");
        assertAuditedViaAi("WEBHOOK_UPDATE", Role.ADMIN);

        ToolResult deleted = confirmed("delete_webhook", "{\"webhookId\":\"" + id + "\"}", Role.ADMIN);
        assertThat(deleted.error()).as(deleted.content()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_webhooks", Integer.class)).isZero();
        assertAuditedViaAi("WEBHOOK_DELETE", Role.ADMIN);
    }

    @Test
    void deleteGitCredentialRemovesAnUnusedCredentialAndKeepsUsedOnes() {
        jdbc.update("INSERT INTO git_credentials (name, username, token_ciphertext, token_hint, created_by) "
                + "SELECT 'cred', 'bob', ?, '...1234', id FROM users WHERE username = 'admin'", new byte[]{1, 2, 3});
        UUID id = jdbc.queryForObject("SELECT id FROM git_credentials", UUID.class);
        jdbc.update("UPDATE deployments SET source_type = 'GIT', git_repo_url = 'https://example.com/r.git', git_ref = 'main', "
                + "git_compose_path = 'compose.yaml', git_credential_id = ? WHERE id = ?", id, deploymentId);

        ToolResult inUse = confirmed("delete_git_credential", "{\"credentialId\":\"" + id + "\"}", Role.ADMIN);
        assertThat(inUse.error()).isTrue();
        assertThat(inUse.content()).contains("CREDENTIAL_IN_USE");

        jdbc.update("DELETE FROM deployments");
        ToolResult deleted = confirmed("delete_git_credential", "{\"credentialId\":\"" + id + "\"}", Role.ADMIN);
        assertThat(deleted.error()).as(deleted.content()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM git_credentials", Integer.class)).isZero();
    }

    @Test
    void normalServiceCallsAreAuditedWithoutAiDetails() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                        "operator", null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR"))));
        try {
            deployments.delete(hostId, deploymentId);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        Map<String, Object> row = auditEntry("DEPLOYMENT_DELETE");
        assertThat(row.get("username")).isEqualTo("operator");
        assertThat(row.get("details")).isNull();
    }
}
