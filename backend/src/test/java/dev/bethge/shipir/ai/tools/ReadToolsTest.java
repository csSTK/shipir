package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.compose.DeploymentService;
import dev.bethge.shipir.compose.dto.EnvEntry;
import dev.bethge.shipir.compose.dto.DeploymentCreateRequest;
import dev.bethge.shipir.compose.dto.DeploymentDetailResponse;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.NetworkDto;
import dev.bethge.shipir.docker.dto.VolumeDto;
import dev.bethge.shipir.host.HostOverviewService;
import dev.bethge.shipir.host.HostResourcesService;
import dev.bethge.shipir.host.dto.HostOverviewResponse;
import dev.bethge.shipir.host.dto.HostDeploymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The read tools against real Postgres and the real host/stack/notification/audit services. Only the services
 * that talk to a Docker daemon or run {@code docker compose} are replaced.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class ReadToolsTest {

    static final Path BASE_DIR = baseDir();
    static final String ENV_SECRET = "env-value-SUPERSECRET-123";
    static final String PEM_SECRET = "PEM-SECRET-MATERIAL";
    static final String WEBHOOK_SECRET = "WEBHOOK-SECRET-PATH-token";

    private static Path baseDir() {
        try {
            return Files.createTempDirectory("shipir-ai-tools-test-");
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
    HostResourcesService resources;
    @MockitoBean
    HostOverviewService overview;

    UUID hostId;
    UUID tcpHostId;
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
        byte[] pem = PEM_SECRET.getBytes(StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, host, port, tls_ca_encrypted, tls_cert_encrypted, "
                + "tls_key_encrypted, owner_user_id) SELECT 'Remote host', 'TCP', '10.0.0.1', 2376, ?, ?, ?, id "
                + "FROM users WHERE username = 'admin'", pem, pem, pem);
        tcpHostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'Remote host'", UUID.class);
        grant("operator", hostId, "OPERATE");
        grant("viewer", hostId, "VIEW");
        grant("operator", tcpHostId, "OPERATE");
        grant("viewer", tcpHostId, "VIEW");
        jdbc.update("INSERT INTO notification_webhooks (name, url_encrypted, url_hint, event_types, created_by) "
                        + "SELECT 'hook', ?, 'https://hooks.example', ARRAY['HOST_DOWN'], id FROM users WHERE username = 'admin'",
                WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8));

        ComposeProcessResult ok = new ComposeProcessResult(0, false, List.of());
        when(execution.execute(any(), any(), any(), any(), any())).thenReturn(ok);
        when(execution.execute(any(), any(), any(), any(), any(), any())).thenReturn(ok);
        DeploymentDetailResponse created = dev.bethge.shipir.common.security.SystemScope.call(() -> deployments.create(hostId, new DeploymentCreateRequest("web",
                "services:\n  web:\n    image: nginx\n", List.of(new EnvEntry("API_TOKEN", ENV_SECRET))), "admin"));
        deploymentId = created.id();
    }

    private void grant(String username, UUID host, String level) {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) SELECT id, ?, ? FROM users WHERE username = ?",
                host, level, username);
    }

    private ToolContext ctx(Role role) {
        String name = role.name().toLowerCase();
        return new ToolContext(users.findByUsername(name).orElseThrow().getId(), name, role);
    }

    private ToolResult run(String tool, String args, Role role) {
        return registry.execute(tool, args, ctx(role));
    }

    private static String host(UUID id) {
        return "{\"hostId\":\"" + id + "\"}";
    }

    private static String stack(UUID host, UUID stack) {
        return "{\"hostId\":\"" + host + "\",\"deploymentId\":\"" + stack + "\"}";
    }

    @Test
    void listHostsReturnsEnvironmentsWithoutTlsMaterial() {
        ToolResult result = run("list_hosts", "{}", Role.VIEWER);

        assertThat(result.error()).isFalse();
        JsonNode hosts = mapper.readTree(result.content());
        assertThat(hosts).hasSize(2);
        assertThat(hosts.get(0).get("name").asString()).isEqualTo("Local host");
        assertThat(hosts.get(1).get("tls").asBoolean()).isTrue();
    }

    @Test
    void hostOverviewDelegatesToOverviewService() {
        when(overview.overview()).thenReturn(List.of(new HostOverviewResponse(hostId, "Local host",
                dev.bethge.shipir.host.ConnectionType.LOCAL, "unix", HostOverviewResponse.Status.DOWN, "UNREACHABLE",
                null, null, null, null, null, null)));

        ToolResult result = run("host_overview", "{}", Role.VIEWER);

        assertThat(mapper.readTree(result.content()).get(0).get("error").asString()).isEqualTo("UNREACHABLE");
    }

    @Test
    void resourceToolsDelegateWithTheHostId() {
        when(resources.deployments(hostId)).thenReturn(List.of());
        when(resources.containers(hostId)).thenReturn(List.of(
                new ContainerDto("c1", "web-1", "nginx", "running", "Up", null, null, List.of(), "web", "web")));
        when(resources.images(hostId)).thenReturn(List.of(new ImageDto("i1", List.of("nginx:latest"), 1L, null, true)));
        when(resources.networks(hostId)).thenReturn(List.of(new NetworkDto("n1", "bridge", "bridge", "local", null, false, 1)));
        when(resources.volumes(hostId)).thenReturn(List.of(new VolumeDto("v1", "local", "/var/lib/v1", null, true)));

        assertThat(run("list_deployments", host(hostId), Role.VIEWER).content()).isEqualTo("[]");
        assertThat(run("list_containers", host(hostId), Role.VIEWER).content()).contains("web-1");
        assertThat(run("list_images", host(hostId), Role.VIEWER).content()).contains("nginx:latest");
        assertThat(run("list_networks", host(hostId), Role.VIEWER).content()).contains("bridge");
        assertThat(run("list_volumes", host(hostId), Role.VIEWER).content()).contains("/var/lib/v1");
        verify(resources).deployments(eq(hostId));
        verify(resources).containers(eq(hostId));
        verify(resources).images(eq(hostId));
        verify(resources).networks(eq(hostId));
        verify(resources).volumes(eq(hostId));
    }

    @Test
    void listToolsOnlyReturnTheGrantedObjects() {
        jdbc.update("DELETE FROM permissions");
        grant("viewer", tcpHostId, "VIEW");

        JsonNode hosts = mapper.readTree(run("list_hosts", "{}", Role.VIEWER).content());
        assertThat(hosts).hasSize(1);
        assertThat(hosts.get(0).get("name").asString()).isEqualTo("Remote host");
        // an admin is not filtered
        assertThat(mapper.readTree(run("list_hosts", "{}", Role.ADMIN).content())).hasSize(2);
    }

    @Test
    void ungrantedHostAndDeploymentAreNotFoundForTools() {
        jdbc.update("DELETE FROM permissions");

        ToolResult detail = run("get_managed_deployment", stack(hostId, deploymentId), Role.OPERATOR);

        assertThat(detail.error()).isTrue();
        assertThat(detail.content()).contains("NOT_FOUND").doesNotContain("nginx");
    }

    @Test
    void deploymentGrantShowsOnlyThatDeployment() {
        jdbc.update("DELETE FROM permissions");
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'VIEW' FROM users WHERE username = 'viewer'",
                deploymentId);

        ToolResult detail = run("get_managed_deployment", stack(hostId, deploymentId), Role.VIEWER);

        assertThat(detail.error()).as(detail.content()).isFalse();
    }

    @Test
    void getManagedStackReturnsYamlAndEnvKeysButNoValues() {
        ToolResult result = run("get_managed_deployment", stack(hostId, deploymentId), Role.VIEWER);

        assertThat(result.error()).isFalse();
        JsonNode json = mapper.readTree(result.content());
        assertThat(json.get("name").asString()).isEqualTo("web");
        assertThat(json.get("yaml").asString()).contains("nginx");
        assertThat(json.get("envKeys")).extracting(JsonNode::asString).containsExactly("API_TOKEN");
        assertThat(result.content()).doesNotContain(ENV_SECRET);
    }

    @Test
    void unknownStackIsAnErrorResultWithTheServiceCode() {
        ToolResult result = run("get_managed_deployment", stack(hostId, UUID.randomUUID()), Role.VIEWER);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("DEPLOYMENT_NOT_FOUND");
    }

    @Test
    void listStackVersionsReturnsHistory() {
        ToolResult result = run("list_deployment_versions", stack(hostId, deploymentId), Role.OPERATOR);

        assertThat(result.error()).isFalse();
        assertThat(mapper.readTree(result.content())).hasSize(1);
        assertThat(result.content()).contains("\"source\":\"MANUAL\"");
    }

    @Test
    void listNotificationsOnlyReturnsTheCallersOwn() {
        UUID viewer = ctx(Role.VIEWER).userId();
        UUID operator = ctx(Role.OPERATOR).userId();
        jdbc.update("INSERT INTO notifications (user_id, type, host_id, host_name, message) VALUES (?, 'HOST_DOWN', ?, 'Local host', 'mine')",
                viewer, hostId);
        jdbc.update("INSERT INTO notifications (user_id, type, host_id, host_name, message) VALUES (?, 'HOST_DOWN', ?, 'Local host', 'theirs')",
                operator, hostId);

        ToolResult result = run("list_notifications", "{\"unreadOnly\":true,\"size\":10}", Role.VIEWER);

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("mine").doesNotContain("theirs");
        assertThat(mapper.readTree(result.content()).get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void listAuditLogIsAdminOnlyAndFilters() {
        jdbc.update("INSERT INTO audit_log (username, action, outcome) VALUES ('admin', 'HOST_CREATE', 'SUCCESS')");
        jdbc.update("INSERT INTO audit_log (username, action, outcome) VALUES ('admin', 'HOST_DELETE', 'SUCCESS')");

        ToolResult admin = run("list_audit_log", "{\"action\":\"HOST_CREATE\"}", Role.ADMIN);

        assertThat(admin.error()).isFalse();
        JsonNode json = mapper.readTree(admin.content());
        assertThat(json.get("totalElements").asLong()).isEqualTo(1);
        assertThat(json.get("items").get(0).get("action").asString()).isEqualTo("HOST_CREATE");

        for (Role role : List.of(Role.OPERATOR, Role.VIEWER)) {
            ToolResult denied = run("list_audit_log", "{}", role);
            assertThat(denied.error()).isTrue();
            assertThat(denied.content()).contains("UNKNOWN_TOOL");
        }
    }

    @Test
    void auditLogRejectsMalformedTimestampsAsArgumentError() {
        ToolResult result = run("list_audit_log", "{\"from\":\"yesterday\"}", Role.ADMIN);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("INVALID_ARGUMENTS", "ISO-8601");
    }

    @Test
    void unknownArgumentsAreRejectedAgainstTheRealSchema() {
        ToolResult result = run("list_containers", "{\"hostId\":\"" + UUID.randomUUID() + "\",\"bogus\":1}", Role.VIEWER);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("INVALID_ARGUMENTS", "unknown argument");
    }

    @Test
    void hostToolsRejectNonUuidAndSurfaceUnknownHost() {
        assertThat(run("list_containers", "{\"hostId\":\"1; drop table\"}", Role.VIEWER).content())
                .contains("INVALID_ARGUMENTS");
        assertThat(run("list_containers", "{}", Role.VIEWER).content()).contains("INVALID_ARGUMENTS");
    }

    @Test
    void roleFilteringOfTheRealToolSet() {
        assertThat(readTools(Role.ADMIN)).extracting(AiTool::name).containsExactlyInAnyOrder(
                "list_hosts", "host_overview", "list_deployments", "get_managed_deployment", "list_deployment_versions",
                "list_containers", "list_images", "list_networks", "list_volumes", "list_notifications",
                "list_git_credentials", "list_audit_log");
        assertThat(readTools(Role.OPERATOR)).extracting(AiTool::name)
                .hasSize(11).contains("list_git_credentials").doesNotContain("list_audit_log");
        assertThat(readTools(Role.VIEWER)).extracting(AiTool::name)
                .hasSize(10).doesNotContain("list_audit_log", "list_git_credentials");
    }

    @Test
    void listGitCredentialsReturnsOnlyIdAndNameToOperatorsAndAdmins() {
        jdbc.update("INSERT INTO git_credentials (name, username, token_ciphertext, token_hint, created_by) "
                + "SELECT 'github', 'bob-the-user', ?, '...HINT9', id FROM users WHERE username = 'admin'", new byte[]{1, 2, 3});
        UUID id = jdbc.queryForObject("SELECT id FROM git_credentials", UUID.class);

        for (Role role : List.of(Role.OPERATOR, Role.ADMIN)) {
            ToolResult result = run("list_git_credentials", "{}", role);
            assertThat(result.error()).as(result.content()).isFalse();
            JsonNode list = mapper.readTree(result.content());
            assertThat(list).hasSize(1);
            assertThat(list.get(0).get("id").asString()).isEqualTo(id.toString());
            assertThat(list.get(0).get("name").asString()).isEqualTo("github");
            assertThat(list.get(0).propertyNames()).containsExactlyInAnyOrder("id", "name");
            assertThat(result.content()).doesNotContain("HINT9").doesNotContain("bob-the-user").doesNotContain("tokenHint");
        }
        ToolResult denied = run("list_git_credentials", "{}", Role.VIEWER);
        assertThat(denied.error()).isTrue();
        assertThat(denied.content()).doesNotContain("github");
    }

    private List<AiTool> readTools(Role role) {
        return registry.toolsFor(role).stream().filter(t -> !t.write()).toList();
    }

    @Test
    void secretsNeverAppearInAnyToolResult() {
        when(resources.deployments(any())).thenReturn(List.of(HostDeploymentResponse.of(
                new dev.bethge.shipir.docker.dto.DeploymentSummaryDto("web", 1, 1,
                        dev.bethge.shipir.docker.dto.DeploymentSummaryDto.Status.RUNNING, null), deploymentId)));
        when(resources.containers(any())).thenReturn(List.of());
        when(resources.images(any())).thenReturn(List.of());
        when(resources.networks(any())).thenReturn(List.of());
        when(resources.volumes(any())).thenReturn(List.of());
        when(overview.overview()).thenReturn(List.of());
        jdbc.update("INSERT INTO audit_log (username, action, outcome, details) VALUES ('admin', 'WEBHOOK_CREATE', 'SUCCESS', '{\"name\":\"hook\"}'::jsonb)");

        Map<String, String> argsByTool = Map.of(
                "get_managed_deployment", stack(hostId, deploymentId),
                "list_deployment_versions", stack(hostId, deploymentId));
        StringBuilder all = new StringBuilder();
        for (AiTool tool : readTools(Role.ADMIN)) {
            String args = argsByTool.getOrDefault(tool.name(),
                    tool.parameters().toString().contains("hostId") ? host(tcpHostId) : "{}");
            ToolResult result = run(tool.name(), args, Role.ADMIN);
            assertThat(result.error()).as(tool.name() + ": " + result.content()).isFalse();
            all.append(result.content());
        }

        assertThat(all.toString())
                .doesNotContain(ENV_SECRET)
                .doesNotContain(PEM_SECRET)
                .doesNotContain(WEBHOOK_SECRET)
                .doesNotContain("hooks.example");
    }
}
