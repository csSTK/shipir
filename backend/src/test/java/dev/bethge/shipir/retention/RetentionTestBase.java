package dev.bethge.shipir.retention;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real Postgres, fixed clock, one user per role with a changed password, default retention (365 / 30). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RetentionTestBase.FixedClockConfig.class})
abstract class RetentionTestBase {

    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    static final String PASSWORD = "A-test-Passw0rd-for-retention!";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM chat_sessions");
        jdbc.update("UPDATE retention_settings SET audit_log_days = 365, notification_days = 30, "
                + "chat_session_days = 90, stats_days = 7, updated_at = now(), updated_by = NULL");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        // setup gate: the admin needs an environment
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Retention host', 'LOCAL', id FROM users WHERE username = 'admin'");
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

    void insertAudit(Instant at, String action) {
        jdbc.update("INSERT INTO audit_log (occurred_at, username, action, outcome) VALUES (?, 'u', ?, 'SUCCESS')",
                java.sql.Timestamp.from(at), action);
    }

    void insertNotification(Instant at) {
        jdbc.update("INSERT INTO notifications (user_id, type, host_name, message, created_at) "
                        + "SELECT id, 'HOST_DOWN', 'h', 'm', ? FROM users WHERE username = 'admin'",
                java.sql.Timestamp.from(at));
    }

    /** Inserts a chat session of the admin user with the given updated_at; returns its id. */
    UUID insertChatSession(Instant updatedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_sessions (id, user_id, title, created_at, updated_at) "
                        + "SELECT ?, id, 'chat', ?, ? FROM users WHERE username = 'admin'",
                id, java.sql.Timestamp.from(updatedAt), java.sql.Timestamp.from(updatedAt));
        return id;
    }

    /** Inserts a stats sample of the retention host at {@code ts}. */
    void insertStatsSample(String containerId, Instant ts) {
        jdbc.update("INSERT INTO stats_samples (host_id, container_id, ts, cpu_percent, mem_bytes, net_rx_bytes, "
                        + "net_tx_bytes, blk_read_bytes, blk_write_bytes) "
                        + "SELECT id, ?, ?, 1, 1, 1, 1, 1, 1 FROM docker_hosts WHERE name = 'Retention host'",
                containerId, java.sql.Timestamp.from(ts));
    }

    void insertChatMessage(UUID sessionId, int seq) {
        jdbc.update("INSERT INTO chat_messages (session_id, seq, role, content) VALUES (?, ?, 'USER', 'hi')",
                sessionId, seq);
    }
}
