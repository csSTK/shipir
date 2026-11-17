package dev.bethge.shipir.notification;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real Postgres, one user per role with a changed password, clean notification tables per test. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
abstract class NotificationTestBase {

    static final String PASSWORD = "A-test-Passw0rd-for-notifs!";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    /** Host the seeded operator and viewer may see; notifications of other hosts are hidden from them. */
    protected UUID visibleHost;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("DELETE FROM notification_preferences");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        visibleHost = insertHost("notif-host");
        for (String name : new String[]{"operator", "viewer"}) {
            jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, 'VIEW')",
                    userId(name), visibleHost);
        }
    }

    UUID insertHost(String name) {
        return jdbc.queryForObject("INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id) "
                + "SELECT ?, 'TCP', 'docker.local', 2375, id FROM users WHERE username = 'admin' RETURNING id", UUID.class, name);
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

    UUID userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", UUID.class, username);
    }

    UUID insertNotification(String username, String type, boolean read, Instant createdAt) {
        return insertNotification(username, type, read, createdAt, visibleHost);
    }

    UUID insertNotification(String username, String type, boolean read, Instant createdAt, UUID hostId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notifications (id, user_id, type, host_id, host_name, message, created_at, read_at) "
                        + "VALUES (?, ?, ?, ?, 'h', 'msg', ?, ?)",
                id, userId(username), type, hostId, java.sql.Timestamp.from(createdAt),
                read ? java.sql.Timestamp.from(createdAt) : null);
        return id;
    }
}
