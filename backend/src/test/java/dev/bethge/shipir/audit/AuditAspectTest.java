package dev.bethge.shipir.audit;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.PasswordResetCodeSender;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, AuditAspectTest.TestBeans.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class AuditAspectTest {

    private static final String PASSWORD = "A-test-Passw0rd-for-audit!";
    private static final String NEW_PASSWORD = "A-brand-new-Passw0rd-audit!";

    static class CapturingSender implements PasswordResetCodeSender {
        final List<String> codes = new ArrayList<>();

        @Override
        public void send(User user, String code) {
            codes.add(code);
        }
    }

    /** Audited method that writes a row and then fails, to prove the FAILURE entry outlives the rollback. */
    static class RollbackProbe {
        private final UserRepository users;

        RollbackProbe(UserRepository users) {
            this.users = users;
        }

        @Auditable(action = "PROBE", targetType = "PROBE", targetId = "#id", username = "#name")
        @Transactional
        public void failAfterWrite(String id, String name) {
            users.saveAndFlush(new User("probe-" + id, "x", Role.VIEWER));
            throw new IllegalStateException("boom");
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        CapturingSender capturingSender() {
            return new CapturingSender();
        }

        @Bean
        RollbackProbe rollbackProbe(UserRepository users) {
            return new RollbackProbe(users);
        }
    }

    @DynamicPropertySource
    static void dockerSocket(DynamicPropertyRegistry registry) {
        registry.add("shipir.docker.local-socket",
                () -> "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath());
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    CapturingSender sender;
    @Autowired
    RollbackProbe probe;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM docker_hosts");
        sender.codes.clear();
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
    }

    private List<Map<String, Object>> rows(String action) {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE action = ? ORDER BY occurred_at", action);
    }

    private Cookie anonCsrf() throws Exception {
        return mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private MvcResult login(String user, String password) throws Exception {
        Cookie csrf = anonCsrf();
        return mvc.perform(post("/api/auth/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, password))).andReturn();
    }

    private record Session(MockHttpSession session, Cookie csrf) {
    }

    private Session loggedIn(String user) throws Exception {
        Cookie csrf = anonCsrf();
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, PASSWORD))).andReturn();
        return new Session((MockHttpSession) r.getRequest().getSession(false), csrf);
    }

    private MvcResult send(Session s, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b)
            throws Exception {
        return mvc.perform(b.session(s.session()).cookie(s.csrf()).header("X-XSRF-TOKEN", s.csrf().getValue())
                .contentType(APPLICATION_JSON)).andReturn();
    }

    @Test
    void successfulLoginWritesOneSuccessRowWithUser() throws Exception {
        assertThat(login("viewer", PASSWORD).getResponse().getStatus()).isEqualTo(200);

        List<Map<String, Object>> rows = rows("LOGIN");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("outcome", "SUCCESS").containsEntry("username", "viewer");
        assertThat(rows.get(0).get("user_id")).isEqualTo(users.findByUsername("viewer").orElseThrow().getId());
    }

    @Test
    void failedLoginWritesFailureWithAttemptedUsernameAndNullUserId() throws Exception {
        MvcResult known = login("viewer", "wrong-password-value");
        MvcResult unknown = login("nobody-here", "wrong-password-value");

        assertThat(known.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(known.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        List<Map<String, Object>> rows = rows("LOGIN");
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r).containsEntry("outcome", "FAILURE");
            assertThat(r.get("user_id")).isNull();
        });
        assertThat(rows).extracting(r -> r.get("username")).containsExactly("viewer", "nobody-here");
    }

    @Test
    void logoutWritesSuccessRowForTheLoggedInUser() throws Exception {
        Session s = loggedIn("operator");
        assertThat(send(s, post("/api/auth/logout")).getResponse().getStatus()).isEqualTo(204);

        List<Map<String, Object>> rows = rows("LOGOUT");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("outcome", "SUCCESS").containsEntry("username", "operator");
    }

    @Test
    void passwordChangeWritesRowsAndNoPasswordLeaks(CapturedOutput output) throws Exception {
        Session s = loggedIn("operator");
        String ok = "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, NEW_PASSWORD);
        String bad = "{\"currentPassword\":\"nope-nope-nope-nope\",\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD);
        assertThat(send(s, post("/api/auth/password").content(bad)).getResponse().getStatus()).isEqualTo(400);
        assertThat(send(s, post("/api/auth/password").content(ok)).getResponse().getStatus()).isEqualTo(204);

        List<Map<String, Object>> rows = rows("PASSWORD_CHANGE");
        assertThat(rows).extracting(r -> r.get("outcome")).containsExactly("FAILURE", "SUCCESS");
        assertThat(rows).allSatisfy(r -> assertThat(r).containsEntry("username", "operator"));
        assertNoSecrets(output, PASSWORD, NEW_PASSWORD, "nope-nope-nope-nope");
    }

    @Test
    void passwordResetConfirmWritesRowsAndNoCodeLeaks(CapturedOutput output) throws Exception {
        Cookie csrf = anonCsrf();
        mvc.perform(post("/api/auth/password-reset/request").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON).content("{\"username\":\"viewer\"}"));
        String code = sender.codes.get(0);
        String wrongCode = code.equals("000000") ? "111111" : "000000";
        String body = "{\"username\":\"viewer\",\"code\":\"%s\",\"newPassword\":\"%s\"}";
        mvc.perform(post("/api/auth/password-reset/confirm").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON).content(body.formatted(wrongCode, NEW_PASSWORD)));
        int ok = mvc.perform(post("/api/auth/password-reset/confirm").cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType(APPLICATION_JSON)
                        .content(body.formatted(code, NEW_PASSWORD))).andReturn().getResponse().getStatus();

        assertThat(ok).isEqualTo(204);
        List<Map<String, Object>> rows = rows("PASSWORD_RESET_CONFIRM");
        assertThat(rows).extracting(r -> r.get("outcome")).containsExactly("FAILURE", "SUCCESS");
        assertThat(rows).allSatisfy(r -> assertThat(r).containsEntry("username", "viewer"));
        assertNoSecrets(output, code, wrongCode, NEW_PASSWORD);
    }

    @Test
    void hostCreateRenameDeleteWriteOneRowEach() throws Exception {
        Session s = loggedIn("admin");
        MvcResult created = send(s, post("/api/hosts")
                .content("{\"name\":\"local\",\"connectionType\":\"LOCAL\"}"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String id = jdbc.queryForObject("SELECT id::text FROM docker_hosts", String.class);
        assertThat(send(s, patch("/api/hosts/" + id).content("{\"name\":\"renamed\"}")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(send(s, delete("/api/hosts/" + id)).getResponse().getStatus()).isEqualTo(204);

        for (String action : List.of("HOST_CREATE", "HOST_RENAME", "HOST_DELETE")) {
            List<Map<String, Object>> rows = rows(action);
            assertThat(rows).as(action).hasSize(1);
            assertThat(rows.get(0)).containsEntry("outcome", "SUCCESS").containsEntry("username", "admin")
                    .containsEntry("target_type", "HOST").containsEntry("target_id", id);
        }
    }

    @Test
    void failedHostActionWritesFailureRow() throws Exception {
        Session s = loggedIn("admin");
        // the setup gate only lets an admin without any environment create one
        send(s, post("/api/hosts").content("{\"name\":\"local\",\"connectionType\":\"LOCAL\"}"));
        String missing = UUID.randomUUID().toString();
        assertThat(send(s, delete("/api/hosts/" + missing)).getResponse().getStatus()).isEqualTo(404);

        List<Map<String, Object>> rows = rows("HOST_DELETE");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("outcome", "FAILURE").containsEntry("target_id", missing);
    }

    @Test
    void failureEntrySurvivesRollbackOfAuditedTransaction() {
        assertThatThrownBy(() -> probe.failAfterWrite("42", "prober")).isInstanceOf(IllegalStateException.class);

        assertThat(users.findByUsername("probe-42")).isEmpty();
        List<Map<String, Object>> rows = rows("PROBE");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("outcome", "FAILURE").containsEntry("username", "prober")
                .containsEntry("target_id", "42");
    }

    private void assertNoSecrets(CapturedOutput output, String... secrets) {
        String dump = jdbc.queryForList("SELECT * FROM audit_log").toString();
        for (String secret : secrets) {
            assertThat(dump).doesNotContain(secret);
            assertThat(output.getAll().lines().filter(l -> l.contains(".audit.") || l.contains(".auth.")))
                    .noneMatch(l -> l.contains(secret));
        }
    }
}
