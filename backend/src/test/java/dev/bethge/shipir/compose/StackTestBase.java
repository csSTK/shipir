package dev.bethge.shipir.compose;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
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
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
abstract class StackTestBase {

    static final String PASSWORD = "A-test-Passw0rd-for-stacks!";
    static final String YAML = "services:\n  web:\n    image: nginx\n";
    static final Path BASE_DIR = createBaseDir();

    private static Path createBaseDir() {
        try {
            return Files.createTempDirectory("shipir-stacks-test-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void stacksDir(DynamicPropertyRegistry registry) {
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
    protected boolean downFails;
    protected volatile boolean upFails;
    /** Blocks every {@code up} until counted down; replaced per test. */
    protected volatile java.util.concurrent.CountDownLatch upGate = new java.util.concurrent.CountDownLatch(0);

    @BeforeEach
    void resetStacks() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM stacks");
        jdbc.update("DELETE FROM docker_hosts");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Stack host', 'LOCAL', id FROM users WHERE username = 'admin'");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        commands.clear();
        downFails = false;
        upFails = false;
        upGate = new java.util.concurrent.CountDownLatch(0);
        composeFiles.clear();
        org.mockito.stubbing.Answer<ComposeProcessResult> fake = inv -> {
            UUID h = inv.getArgument(0);
            String name = inv.getArgument(1);
            ComposeCommand command = inv.getArgument(2);
            commands.add(command);
            composeFiles.add(inv.getArguments().length > 5 ? inv.getArgument(5) : null);
            if (command == ComposeCommand.DOWN) {
                return downFails ? new ComposeProcessResult(1, false, List.of("cannot reach daemon"))
                        : new ComposeProcessResult(0, false, List.of());
            }
            if (command == ComposeCommand.UP) {
                java.util.function.Consumer<String> onLine = inv.getArgument(4);
                onLine.accept("Container web  Starting");
                upGate.await();
                onLine.accept(upFails ? "Error: boom" : "Container web  Started");
                return upFails ? new ComposeProcessResult(1, false, List.of("Error: boom"))
                        : new ComposeProcessResult(0, false, List.of("Container web  Started"));
            }
            String yaml = files.readCompose(h, name).orElse("");
            return yaml.contains("INVALID")
                    ? new ComposeProcessResult(1, false, List.of("yaml: line 2: INVALID mapping"))
                    : new ComposeProcessResult(0, false, List.of());
        };
        org.mockito.Mockito.doAnswer(fake).when(execution).execute(any(), any(), any(), any(), any());
        org.mockito.Mockito.doAnswer(fake).when(execution).execute(any(), any(), any(), any(), any(), any());
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
        return "/api/hosts/" + hostId + "/managed-stacks";
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
