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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
        jdbc.update("DELETE FROM teams");
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
        when(execution.execute(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(ok);
        // the fixture is created by a signed-in ADMIN: without a subject the compose policy runs (system trust is not admin)
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("admin", "x", List.of()));
        try {
            DeploymentDetailResponse created = deployments.create(hostId, new DeploymentCreateRequest("web",
                    "services:\n  web:\n    image: nginx\n", List.of(new EnvEntry("API_TOKEN", ENV_SECRET))), "admin");
            deploymentId = created.id();
        } finally {
            SecurityContextHolder.clearContext();
        }
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

    private static String configArgs(UUID host, UUID stack, String path) {
        return "{\"hostId\":\"" + host + "\",\"deploymentId\":\"" + stack + "\",\"path\":\"" + path + "\"}";
    }

    private Path writeFile(String rel, String content) throws IOException {
        Path dir = dev.bethge.shipir.common.security.SystemScope.call(() -> deployments.directoryOf(hostId, deploymentId));
        Path file = dir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
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
    void systemManagedDeploymentIsForbiddenForNonAdminToolCallsAndOpenToAdmin() {
        jdbc.update("UPDATE deployments SET system_managed = TRUE WHERE id = ?", deploymentId);

        for (Role role : List.of(Role.OPERATOR, Role.VIEWER)) {
            ToolResult denied = run("get_managed_deployment", stack(hostId, deploymentId), role);
            assertThat(denied.error()).as(role.name()).isTrue();
            assertThat(denied.content()).contains("FORBIDDEN").doesNotContain("nginx").doesNotContain("API_TOKEN")
                    .doesNotContain(ENV_SECRET);
        }
        ToolResult admin = run("get_managed_deployment", stack(hostId, deploymentId), Role.ADMIN);
        assertThat(admin.error()).as(admin.content()).isFalse();
        assertThat(admin.content()).contains("nginx").doesNotContain(ENV_SECRET);
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
    void noAiToolEverReturnsEnvValuesOrEnvLikeFileContentsForAnyRole() throws IOException {
        String fileSecret = "FILE-CONTENT-SUPERSECRET-456";
        Path dir = dev.bethge.shipir.common.security.SystemScope.call(() -> deployments.directoryOf(hostId, deploymentId));
        Files.createDirectories(dir.resolve("repo"));
        Files.createDirectories(dir.resolve("secrets"));
        for (String name : List.of("db.env", "repo/.env", ".env.production", "secrets/token.txt", "tls.pem", "tls.key", "Repo/.ENV")) {
            Path file = dir.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "PASSWORD=" + fileSecret + "\n");
        }

        for (Role role : Role.values()) {
            for (AiTool tool : registry.toolsFor(role)) {
                if (!List.of("list_config_files", "read_config_file").contains(tool.name())) {
                    assertThat(tool.name()).as("only the two config tools may touch files").doesNotContainIgnoringCase("file")
                            .doesNotContainIgnoringCase("config").doesNotContainIgnoringCase("env_");
                }
                if (tool.write()) {
                    continue;
                }
                ToolResult result = run(tool.name(), argsFor(tool), role);
                assertThat(result.content()).as(tool.name() + " as " + role)
                        .doesNotContain(ENV_SECRET).doesNotContain(fileSecret);
            }
        }
    }

    /** Arguments that satisfy most schemas: the test host and deployment for ids, a plain string otherwise. */
    @SuppressWarnings("unchecked")
    private String argsFor(AiTool tool) {
        Map<String, Object> props = (Map<String, Object>) tool.parameters().getOrDefault("properties", Map.of());
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        for (String name : props.keySet()) {
            args.put(name, switch (name) {
                case "hostId" -> hostId.toString();
                case "deploymentId" -> deploymentId.toString();
                case "path" -> ".env.production";
                default -> "web";
            });
        }
        return mapper.writeValueAsString(args);
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
                "list_git_credentials", "list_audit_log", "list_config_files", "read_config_file", "list_access_grants");
        assertThat(readTools(Role.OPERATOR)).extracting(AiTool::name)
                .hasSize(13).contains("list_git_credentials", "read_config_file").doesNotContain("list_audit_log");
        assertThat(readTools(Role.VIEWER)).extracting(AiTool::name)
                .hasSize(12).contains("list_config_files", "read_config_file")
                .doesNotContain("list_audit_log", "list_git_credentials");
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
    void secretsNeverAppearInAnyToolResult() throws IOException {
        writeFile("app.conf", "listen 80;\n");
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
                "list_deployment_versions", stack(hostId, deploymentId),
                "list_config_files", stack(hostId, deploymentId),
                "read_config_file", configArgs(hostId, deploymentId, "app.conf"),
                "list_access_grants", "{\"hostId\":\"" + tcpHostId + "\"}");
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

    // ---- list_config_files / read_config_file ----

    @Test
    void configToolsReturnTreeAndContentForAViewableDeployment() throws IOException {
        writeFile("nginx/default.conf", "server { listen 80; }\n");

        for (Role role : Role.values()) {
            ToolResult tree = run("list_config_files", stack(hostId, deploymentId), role);
            assertThat(tree.error()).as(tree.content()).isFalse();
            assertThat(mapper.readTree(tree.content())).extracting(n -> n.get("path").asString())
                    .contains("nginx", "nginx/default.conf").doesNotContain("compose.yaml", ".env");

            ToolResult file = run("read_config_file", configArgs(hostId, deploymentId, "nginx/default.conf"), role);
            assertThat(file.error()).as(file.content()).isFalse();
            assertThat(mapper.readTree(file.content()).get("content").asString()).isEqualTo("server { listen 80; }\n");
        }
    }

    @Test
    void configToolsAreNotFoundForInvisibleHostAndDeployment() throws IOException {
        writeFile("app.conf", "visible-only-with-grant");
        jdbc.update("DELETE FROM permissions");

        for (String tool : List.of("list_config_files", "read_config_file")) {
            ToolResult result = run(tool, configArgs(hostId, deploymentId, "app.conf"), Role.VIEWER);
            if (tool.equals("list_config_files")) {
                result = run(tool, stack(hostId, deploymentId), Role.VIEWER);
            }
            assertThat(result.error()).as(tool).isTrue();
            assertThat(result.content()).contains("NOT_FOUND").doesNotContain("visible-only-with-grant").doesNotContain("app.conf");
        }
        ToolResult unknown = run("read_config_file", configArgs(hostId, UUID.randomUUID(), "app.conf"), Role.ADMIN);
        assertThat(unknown.error()).isTrue();
        assertThat(unknown.content()).contains("NOT_FOUND");
    }

    @Test
    void readConfigFileNeverReturnsEnvLikeContentNotEvenToOperatorsAndAdmins() throws IOException {
        String secret = "ENV-FILE-SECRET-789";
        for (String name : List.of(".env.production", "db.env", "tls.pem", "tls.key", "secrets/token.txt", "Repo/.ENV")) {
            writeFile(name, "PASSWORD=" + secret + "\n");
        }

        for (Role role : Role.values()) {
            for (String name : List.of(".env.production", "db.env", "tls.pem", "tls.key", "secrets/token.txt",
                    "Repo/.ENV", "x/../db.env", "./secrets/./token.txt")) {
                ToolResult result = run("read_config_file", configArgs(hostId, deploymentId, name), role);
                assertThat(result.error()).as(role + " " + name).isTrue();
                assertThat(result.content()).contains(name.contains("..") ? "INVALID_PATH" : "ENV_FILE_RESTRICTED")
                        .doesNotContain(secret);
            }
        }
    }

    @Test
    void readConfigFileDeniesExcludedSymlinkAndTraversalPaths() throws IOException {
        writeFile("app.conf", "ok");
        Path dir = dev.bethge.shipir.common.security.SystemScope.call(() -> deployments.directoryOf(hostId, deploymentId));
        Path outside = Files.createTempFile("shipir-outside-", ".txt");
        Files.writeString(outside, "OUTSIDE-SECRET");
        Files.createSymbolicLink(dir.resolve("link.conf"), outside);
        Files.createDirectories(dir.resolve("realdir"));
        Files.writeString(dir.resolve("realdir/inner.conf"), "INNER");
        Files.createSymbolicLink(dir.resolve("linkdir"), dir.resolve("realdir"));
        Files.writeString(dir.resolve(".env"), "API_TOKEN=" + ENV_SECRET);

        ToolResult control = run("read_config_file", configArgs(hostId, deploymentId, "app.conf"), Role.OPERATOR);
        assertThat(control.error()).as(control.content()).isFalse();
        assertThat(control.content()).contains("\"content\":\"ok\"");

        Map<String, String> expectedCodes = new java.util.LinkedHashMap<>();
        expectedCodes.put("compose.yaml", "EXCLUDED_PATH");
        expectedCodes.put(".env", "EXCLUDED_PATH");
        expectedCodes.put(".shipir-backups/x", "EXCLUDED_PATH");
        expectedCodes.put("../secret", "INVALID_PATH");
        expectedCodes.put("../../etc/passwd", "INVALID_PATH");
        expectedCodes.put("/etc/passwd", "INVALID_PATH");
        expectedCodes.put("%2e%2e/%2e%2e/etc/passwd", "INVALID_PATH");
        expectedCodes.put("..%2f..%2fetc/passwd", "INVALID_PATH");
        expectedCodes.put("app.conf/../../x", "INVALID_PATH");
        expectedCodes.put("link.conf", "INVALID_PATH");
        expectedCodes.put("linkdir/inner.conf", "INVALID_PATH");
        expectedCodes.forEach((path, code) -> {
            ToolResult result = run("read_config_file", configArgs(hostId, deploymentId, path), Role.OPERATOR);
            assertThat(result.error()).as(path + " -> " + result.content()).isTrue();
            assertThat(mapper.readTree(result.content()).get("error").asString()).as(path).isEqualTo(code);
            assertThat(result.content()).doesNotContain("OUTSIDE-SECRET").doesNotContain("INNER")
                    .doesNotContain(ENV_SECRET).doesNotContain("root:");
        });
        ToolResult tree = run("list_config_files", stack(hostId, deploymentId), Role.OPERATOR);
        assertThat(tree.error()).as(tree.content()).isFalse();
        assertThat(tree.content()).contains("app.conf").doesNotContain("link.conf").doesNotContain("linkdir");
    }

    @Test
    void largeConfigFileIsTruncatedWithTheNote() throws IOException {
        writeFile("big.conf", "line of config\n".repeat(2000));

        ToolResult result = run("read_config_file", configArgs(hostId, deploymentId, "big.conf"), Role.VIEWER);

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("RESULT TRUNCATED").hasSizeLessThan(5000);
    }

    @Test
    void readConfigFileRequiresAPathArgument() {
        ToolResult result = run("read_config_file", stack(hostId, deploymentId), Role.VIEWER);

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("INVALID_ARGUMENTS");
    }

    // ---- list_access_grants (ADMIN only) ----

    private UUID team(String name, String member) {
        jdbc.update("INSERT INTO teams (name) VALUES (?)", name);
        UUID id = jdbc.queryForObject("SELECT id FROM teams WHERE name = ?", UUID.class, name);
        if (member != null) {
            jdbc.update("INSERT INTO team_members (team_id, user_id) SELECT ?, id FROM users WHERE username = ?", id, member);
        }
        return id;
    }

    private static String only(String key, UUID id) {
        return "{\"" + key + "\":\"" + id + "\"}";
    }

    @Test
    void listAccessGrantsForAHostNamesUsersAndTeamsWithLevels() {
        UUID ops = team("ops", "viewer");
        jdbc.update("INSERT INTO permissions (team_id, host_id, level) VALUES (?, ?, 'VIEW')", ops, hostId);

        ToolResult result = run("list_access_grants", only("hostId", hostId), Role.ADMIN);

        assertThat(result.error()).as(result.content()).isFalse();
        JsonNode grants = mapper.readTree(result.content()).get("grants");
        assertThat(grants).hasSize(3);
        List<String> rows = new java.util.ArrayList<>();
        for (JsonNode g : grants) {
            assertThat(g.propertyNames()).containsExactlyInAnyOrder("subjectType", "subjectId", "subjectName", "level");
            rows.add(g.get("subjectType").asString() + ":" + g.get("subjectName").asString() + ":" + g.get("level").asString());
        }
        assertThat(rows).containsExactlyInAnyOrder("USER:operator:OPERATE", "USER:viewer:VIEW", "TEAM:ops:VIEW");
        assertThat(result.content()).doesNotContain("passwordHash").doesNotContain("email");
    }

    @Test
    void listAccessGrantsForADeploymentOnlyReturnsGrantsOnThatDeployment() {
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, 'VIEW' FROM users WHERE username = 'viewer'",
                deploymentId);

        ToolResult result = run("list_access_grants", only("deploymentId", deploymentId), Role.ADMIN);

        assertThat(result.error()).as(result.content()).isFalse();
        JsonNode grants = mapper.readTree(result.content()).get("grants");
        assertThat(grants).hasSize(1);
        assertThat(grants.get(0).get("subjectName").asString()).isEqualTo("viewer");
        assertThat(grants.get(0).get("subjectType").asString()).isEqualTo("USER");
        assertThat(grants.get(0).get("level").asString()).isEqualTo("VIEW");
    }

    @Test
    void listAccessGrantsForAUserReturnsTheirDirectGrants() {
        UUID operator = ctx(Role.OPERATOR).userId();

        ToolResult result = run("list_access_grants", only("userId", operator), Role.ADMIN);

        assertThat(result.error()).as(result.content()).isFalse();
        JsonNode grants = mapper.readTree(result.content()).get("grants");
        assertThat(grants).hasSize(2);
        for (JsonNode g : grants) {
            assertThat(g.get("level").asString()).isEqualTo("OPERATE");
            assertThat(g.get("hostId").asString()).isIn(hostId.toString(), tcpHostId.toString());
        }
    }

    @Test
    void listAccessGrantsForATeamReturnsMembersAndGrants() {
        UUID ops = team("ops", "operator");
        jdbc.update("INSERT INTO permissions (team_id, host_id, level) VALUES (?, ?, 'VIEW')", ops, hostId);

        ToolResult result = run("list_access_grants", only("teamId", ops), Role.ADMIN);

        assertThat(result.error()).as(result.content()).isFalse();
        JsonNode json = mapper.readTree(result.content());
        assertThat(json.get("name").asString()).isEqualTo("ops");
        assertThat(json.get("members")).hasSize(1);
        assertThat(json.get("members").get(0).get("username").asString()).isEqualTo("operator");
        assertThat(json.get("grants")).hasSize(1);
        assertThat(json.get("grants").get(0).get("hostId").asString()).isEqualTo(hostId.toString());
        assertThat(json.get("grants").get(0).get("level").asString()).isEqualTo("VIEW");
    }

    @Test
    void listAccessGrantsReportsUnknownIdsWithSpecificCodes() {
        assertThat(run("list_access_grants", only("hostId", UUID.randomUUID()), Role.ADMIN).content())
                .contains("UNKNOWN_HOST");
        assertThat(run("list_access_grants", only("deploymentId", UUID.randomUUID()), Role.ADMIN).content())
                .contains("UNKNOWN_DEPLOYMENT");
        assertThat(run("list_access_grants", only("userId", UUID.randomUUID()), Role.ADMIN).content())
                .contains("USER_NOT_FOUND");
        assertThat(run("list_access_grants", only("teamId", UUID.randomUUID()), Role.ADMIN).content())
                .contains("TEAM_NOT_FOUND");
        assertThat(run("list_access_grants", only("userId", UUID.randomUUID()), Role.ADMIN).error()).isTrue();
    }

    @Test
    void listAccessGrantsNeedsExactlyOneSubjectArgument() {
        assertThat(run("list_access_grants", "{}", Role.ADMIN).content()).contains("INVALID_ARGUMENTS");
        assertThat(run("list_access_grants", "{\"hostId\":\"" + hostId + "\",\"userId\":\"" + UUID.randomUUID() + "\"}",
                Role.ADMIN).content()).contains("INVALID_ARGUMENTS");
    }

    @Test
    void listAccessGrantsIsNotOfferedToOperatorsAndViewersAndExecuteIsDenied() {
        for (Role role : List.of(Role.OPERATOR, Role.VIEWER)) {
            assertThat(registry.toolsFor(role)).extracting(AiTool::name).doesNotContain("list_access_grants");
            assertThat(registry.definitionsFor(role).toString()).doesNotContain("list_access_grants");
            ToolResult denied = run("list_access_grants", only("hostId", hostId), role);
            assertThat(denied.error()).isTrue();
            assertThat(denied.content()).contains("UNKNOWN_TOOL").doesNotContain("operator").doesNotContain("viewer");
        }
    }
}
