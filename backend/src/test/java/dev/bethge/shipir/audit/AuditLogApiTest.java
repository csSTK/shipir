package dev.bethge.shipir.audit;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class AuditLogApiTest {

    private static final String PASSWORD = "A-test-Passw0rd-for-audit-api!";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        // an ADMIN without any environment is held back by the setup gate
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Audit host', 'LOCAL', id FROM users WHERE username = 'admin'");
        seed();
    }

    /** Logging in is itself audited, so the fixture rows are re-created after every login. */
    private void seed() {
        jdbc.update("DELETE FROM audit_log");
        insert("2026-01-01T10:00:00Z", "alice", "HOST_CREATE");
        insert("2026-01-02T10:00:00Z", "bob", "LOGIN");
        insert("2026-01-03T10:00:00Z", "alice", "LOGIN");
    }

    private void insert(String at, String username, String action) {
        jdbc.update("INSERT INTO audit_log (occurred_at, username, action, outcome) VALUES (?::timestamptz, ?, ?, 'SUCCESS')",
                at, username, action);
    }

    private MockHttpSession login(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login")
                .cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        seed();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void adminGetsNewestFirstWithPagingMetadata() throws Exception {
        mvc.perform(get("/api/audit-log").session(login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[*].occurredAt", hasSize(3)))
                .andExpect(jsonPath("$.items[*].username", contains("alice", "bob", "alice")))
                .andExpect(jsonPath("$.items[0].action").value("LOGIN"))
                .andExpect(jsonPath("$.items[0].outcome").value("SUCCESS"));
    }

    @Test
    void pagesResults() throws Exception {
        mvc.perform(get("/api/audit-log?page=1&size=2").session(login("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].action").value("HOST_CREATE"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    void filtersByActionUsernameAndRange() throws Exception {
        MockHttpSession admin = login("admin");
        mvc.perform(get("/api/audit-log?action=LOGIN").session(admin))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/audit-log?username=alice&action=LOGIN").session(admin))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/audit-log?from=2026-01-02T00:00:00Z&to=2026-01-02T23:59:59Z").session(admin))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].username").value("bob"));
    }

    @Test
    void sizeAbove100IsRejected() throws Exception {
        mvc.perform(get("/api/audit-log?size=101").session(login("admin")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void invalidDateAndPageAreRejected() throws Exception {
        MockHttpSession admin = login("admin");
        mvc.perform(get("/api/audit-log?from=yesterday").session(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/audit-log?page=-1").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/audit-log?size=0").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/audit-log?page=abc").session(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void operatorAndViewerAreForbiddenAnonymousUnauthorized() throws Exception {
        mvc.perform(get("/api/audit-log").session(login("operator"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit-log").session(login("viewer"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit-log")).andExpect(status().isUnauthorized());
    }
}
