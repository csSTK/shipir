package dev.bethge.shipir.compose;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeOptions;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Real Postgres and file repository (temp base dir), one user per role, one LOCAL host. {@code docker compose} is
 * replaced by a fake: {@code config} fails when the compose file contains INVALID, {@code down} is recorded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
abstract class DeploymentTestBase {

    static final String PASSWORD = "A-test-Passw0rd-for-deployments!";
    static final String YAML = "services:\n  web:\n    image: nginx\n";
    static final Path BASE_DIR = createBaseDir();

    private static Path createBaseDir() {
        try {
            return Files.createTempDirectory("shipir-deployments-test-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void deploymentsDir(DynamicPropertyRegistry registry) {
        registry.add("shipir.stacks.base-dir", BASE_DIR::toString);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    ComposeFileRepository files;
    @MockitoBean
    ComposeExecutionService execution;

    protected UUID hostId;
    protected final List<ComposeCommand> commands = new CopyOnWriteArrayList<>();
    /** Compose file passed to each execution (null = default {@code compose.yaml}); parallel to {@link #commands}. */
    protected final List<Path> composeFiles = new CopyOnWriteArrayList<>();
    /** Content of the override file ({@code -f} after the main file) of each execution that had one, read at call time. */
    protected final List<String> overrideYamls = new CopyOnWriteArrayList<>();
    /** Profiles/services of each recorded execution ({@link ComposeOptions#NONE} for the overloads without options); parallel to {@link #commands}. */
    protected final List<ComposeOptions> options = new CopyOnWriteArrayList<>();
    protected boolean downFails;
    protected volatile boolean upFails;
    protected volatile boolean pullFails;
    /** Makes {@code up} throw (compose cannot be run) instead of returning a result. */
    protected volatile boolean upThrows;
    /** Blocks every {@code up} until counted down; replaced per test. */
    protected volatile java.util.concurrent.CountDownLatch upGate = new java.util.concurrent.CountDownLatch(0);

    @BeforeEach
    void resetDeployments() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM docker_hosts");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Deployment host', 'LOCAL', id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        grantHost("operator", "OPERATE");
        grantHost("viewer", "VIEW");
        commands.clear();
        downFails = false;
        upFails = false;
        pullFails = false;
        upThrows = false;
        upGate = new java.util.concurrent.CountDownLatch(0);
        composeFiles.clear();
        overrideYamls.clear();
        options.clear();
        org.mockito.stubbing.Answer<ComposeProcessResult> fake = inv -> {
            UUID h = inv.getArgument(0);
            String name = inv.getArgument(1);
            ComposeCommand command = inv.getArgument(2);
            if (command == ComposeCommand.CONFIG_JSON) {
                // the ADMIN-gate lookup, not part of the flow under test: not recorded in `commands`.
                // Stands in for `docker compose config --format json`: the YAML as JSON (no anchor/path resolution)
                java.util.Map<String, Object> config = new java.util.LinkedHashMap<>();
                config.put("name", name);
                config.putAll(tools.jackson.dataformat.yaml.YAMLMapper.builder().build().readValue(
                        files.readCompose(h, name).orElse(""), java.util.Map.class));
                // like compose: a service behind a profile is only part of the output when that profile (or `*`) is enabled
                List<String> active = inv.getArguments().length > 7
                        ? ((ComposeOptions) inv.getArgument(7)).profiles() : List.of();
                if (config.get("services") instanceof java.util.Map<?, ?> all) {
                    java.util.Map<Object, Object> kept = new java.util.LinkedHashMap<>();
                    all.forEach((k, v) -> {
                        boolean gated = v instanceof java.util.Map<?, ?> svc && svc.get("profiles") instanceof List<?> ps
                                && !ps.isEmpty();
                        boolean enabled = !gated || active.contains("*")
                                || ((List<?>) ((java.util.Map<?, ?>) v).get("profiles")).stream().anyMatch(active::contains);
                        if (enabled) {
                            kept.put(k, v);
                        }
                    });
                    config.put("services", kept);
                }
                String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(config);
                return new ComposeProcessResult(0, false, List.of("WARN[0000] a warning on stderr", json));
            }
            if (command == ComposeCommand.CONFIG_SERVICES || command == ComposeCommand.CONFIG_PROFILES) {
                // `docker compose --profile "*" config --services|--profiles`: names from the stored YAML
                java.util.Map<?, ?> parsed = tools.jackson.dataformat.yaml.YAMLMapper.builder().build().readValue(
                        files.readCompose(h, name).orElse(""), java.util.Map.class);
                java.util.Set<String> out = new java.util.TreeSet<>();
                java.util.Map<?, ?> services = (java.util.Map<?, ?>) parsed.get("services");
                for (java.util.Map.Entry<?, ?> e : services.entrySet()) {
                    if (command == ComposeCommand.CONFIG_SERVICES) {
                        out.add((String) e.getKey());
                    } else if (e.getValue() instanceof java.util.Map<?, ?> svc && svc.get("profiles") instanceof List<?> ps) {
                        ps.forEach(p -> out.add((String) p));
                    }
                }
                return new ComposeProcessResult(0, false, new ArrayList<>(out));
            }
            commands.add(command);
            composeFiles.add(inv.getArguments().length > 5 ? inv.getArgument(5) : null);
            options.add(inv.getArguments().length > 7 ? inv.getArgument(7) : ComposeOptions.NONE);
            if (inv.getArguments().length > 6 && inv.getArgument(6) != null) {
                Path override = inv.getArgument(6);
                overrideYamls.add(Files.readString(override));
            }
            if (command == ComposeCommand.DOWN) {
                return downFails ? new ComposeProcessResult(1, false, List.of("cannot reach daemon"))
                        : new ComposeProcessResult(0, false, List.of());
            }
            if (command == ComposeCommand.PULL) {
                java.util.function.Consumer<String> onLine = inv.getArgument(4);
                onLine.accept("web Pulling");
                return pullFails ? new ComposeProcessResult(1, false, List.of("Error: pull denied"))
                        : new ComposeProcessResult(0, false, List.of());
            }
            if (command == ComposeCommand.RESTART) {
                return new ComposeProcessResult(upFails ? 1 : 0, false, List.of());
            }
            if (command == ComposeCommand.UP || command == ComposeCommand.UP_SERVICE) {
                java.util.function.Consumer<String> onLine = inv.getArgument(4);
                onLine.accept("Container web  Starting");
                upGate.await();
                if (upThrows) {
                    throw new IllegalStateException("compose binary missing");
                }
                onLine.accept(upFails ? "Error: boom" : "Container web  Started");
                return upFails ? new ComposeProcessResult(1, false, List.of("Error: boom"))
                        : new ComposeProcessResult(0, false, List.of("Container web  Started"));
            }
            String yaml = files.readCompose(h, name).orElse("");
            if (inv.getArguments().length > 5 && inv.getArgument(5) instanceof Path staged
                    && staged.toAbsolutePath().normalize().startsWith(files.stagingDir(h, name))) {
                yaml = Files.readString(staged); // validation of a staged Git checkout (not live yet)
            }
            return yaml.contains("INVALID")
                    ? new ComposeProcessResult(1, false, List.of("yaml: line 2: INVALID mapping"))
                    : new ComposeProcessResult(0, false, List.of());
        };
        org.mockito.Mockito.doAnswer(fake).when(execution).execute(any(), any(), any(), any(), any());
        org.mockito.Mockito.doAnswer(fake).when(execution).execute(any(), any(), any(), any(), any(), any());
        org.mockito.Mockito.doAnswer(fake).when(execution).execute(any(), any(), any(), any(), any(), any(), any());
        org.mockito.Mockito.doAnswer(fake).when(execution).execute(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** Host-level grant for a seeded user; cascades away with the host. */
    protected void grantHost(String username, String level) {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) SELECT id, ?, ? FROM users WHERE username = ?",
                hostId, level, username);
    }

    protected void grantDeployment(String username, UUID deploymentId, String level) {
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) SELECT id, ?, ? FROM users WHERE username = ?",
                deploymentId, level, username);
    }

    record Login(MockHttpSession session, Cookie csrf) {
        MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
            return b.session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    Login login(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login")
                .cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Login((MockHttpSession) r.getRequest().getSession(false), anon);
    }

    String url() {
        return "/api/hosts/" + hostId + "/managed-deployments";
    }

    static String createJson(String name, String yaml, String envJson) {
        String env = envJson == null ? "" : ",\"env\":" + envJson;
        return "{\"name\":\"%s\",\"yaml\":\"%s\"%s}".formatted(name, yaml.replace("\n", "\\n"), env);
    }

    static String updateJson(String yaml, String envJson) {
        String env = envJson == null ? "" : ",\"env\":" + envJson;
        return "{\"yaml\":\"%s\"%s}".formatted(yaml.replace("\n", "\\n"), env);
    }

    static List<String> noCommands() {
        return new ArrayList<>();
    }
}
