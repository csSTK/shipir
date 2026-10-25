package dev.bethge.shipir.permission;

import dev.bethge.shipir.PostgresTestConfiguration;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PermissionApiTest {

    private static final String ADMIN_PASSWORD = "test-initial-admin-password";
    private static final String PW = "Some-Long-Passw0rd-123!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JsonMapper json;

    private UUID adminId;
    private UUID host;
    private UUID dep;
    private UUID operatorId;
    private UUID viewerId;

    @BeforeEach
    void data() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM permissions");
        jdbc.update("DELETE FROM teams");
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("DELETE FROM users WHERE username <> 'admin'");
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", UUID.class);
        jdbc.update("UPDATE users SET password_hash = ?, password_changed_at = now() WHERE username = 'admin'",
                encoder.encode(ADMIN_PASSWORD));
        host = jdbc.queryForObject("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "VALUES ('h', 'LOCAL', ?) RETURNING id", UUID.class, adminId);
        dep = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "VALUES ('d', ?, 'x', ?) RETURNING id", UUID.class, host, adminId);
        operatorId = user("oper", "OPERATOR");
        viewerId = user("viewer", "VIEWER");
    }

    @org.junit.jupiter.api.AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM permissions");
        jdbc.update("DELETE FROM teams");
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("DELETE FROM users WHERE username <> 'admin'");
    }

    private UUID user(String name, String role) {
        return jdbc.queryForObject("INSERT INTO users (username, password_hash, role, password_changed_at) "
                + "VALUES (?, ?, ?, now()) RETURNING id", UUID.class, name, encoder.encode(PW), role);
    }

    private record Session(MockHttpSession session, Cookie csrf) {
    }

    private Session login(String user, String password) throws Exception {
        Cookie c = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(c).header("X-XSRF-TOKEN", c.getValue())
                .contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", user, "password", password)))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Session((MockHttpSession) r.getRequest().getSession(false), c);
    }

    private ResultActions call(Session s, boolean put, UUID userId, String body) throws Exception {
        var b = put ? put("/api/users/" + userId + "/permissions") : get("/api/users/" + userId + "/permissions");
        b.session(s.session).cookie(s.csrf).header("X-XSRF-TOKEN", s.csrf.getValue());
        if (body != null) {
            b.contentType(APPLICATION_JSON).content(body);
        }
        return mvc.perform(b);
    }

    private String grants(String... items) {
        return "{\"grants\":[" + String.join(",", items) + "]}";
    }

    private String hostGrant(UUID id, String level) {
        return "{\"hostId\":\"%s\",\"level\":\"%s\"}".formatted(id, level);
    }

    private String depGrant(UUID id, String level) {
        return "{\"deploymentId\":\"%s\",\"level\":\"%s\"}".formatted(id, level);
    }

    private long audit(String outcome) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'PERMISSION_UPDATE' AND outcome = ?",
                Long.class, outcome);
    }

    @Test
    void onlyAdminsMayReadOrWrite() throws Exception {
        Session op = login("oper", PW);
        Session viewer = login("viewer", PW);
        for (Session s : new Session[]{op, viewer}) {
            call(s, false, viewerId, null).andExpect(status().isForbidden());
            call(s, true, viewerId, grants(hostGrant(host, "VIEW"))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/users/" + viewerId + "/permissions")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permissions", Long.class)).isZero();
    }

    @Test
    void putRequiresCsrf() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        mvc.perform(put("/api/users/" + operatorId + "/permissions").session(admin.session).cookie(admin.csrf)
                        .contentType(APPLICATION_JSON).content(grants()))
                .andExpect(status().isForbidden());
    }

    @Test
    void putReplacesFullSetAndGetReturnsIt() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        call(admin, false, operatorId, null).andExpect(status().isOk()).andExpect(jsonPath("$.grants.length()").value(0));

        call(admin, true, operatorId, grants(hostGrant(host, "VIEW"), depGrant(dep, "OPERATE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grants.length()").value(2));
        call(admin, false, operatorId, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.grants[?(@.deploymentId=='%s')].level".formatted(dep)).value("OPERATE"))
                .andExpect(jsonPath("$.grants[?(@.deploymentId=='%s')].deploymentHostId".formatted(dep)).value(host.toString()))
                .andExpect(jsonPath("$.grants[?(@.hostId=='%s')].level".formatted(host)).value("VIEW"));

        // replace: only the host grant now, upgraded
        call(admin, true, operatorId, grants(hostGrant(host, "OPERATE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permissions WHERE user_id = ?", Long.class, operatorId)).isEqualTo(1);

        // empty set clears
        call(admin, true, operatorId, grants()).andExpect(status().isOk()).andExpect(jsonPath("$.grants.length()").value(0));
        assertThat(audit("SUCCESS")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT target_id FROM audit_log WHERE action = 'PERMISSION_UPDATE' LIMIT 1",
                String.class)).isEqualTo(operatorId.toString());
    }

    @Test
    void rejectsInvalidGrantsAndKeepsOldSet() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        call(admin, true, operatorId, grants(hostGrant(host, "VIEW"))).andExpect(status().isOk());

        call(admin, true, adminId, grants(hostGrant(host, "VIEW"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADMIN_NEEDS_NO_GRANTS"));
        call(admin, true, adminId, grants()).andExpect(status().isOk());
        call(admin, true, UUID.randomUUID(), grants()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        call(admin, false, UUID.randomUUID(), null).andExpect(status().isNotFound());
        call(admin, true, operatorId, grants(hostGrant(UUID.randomUUID(), "VIEW"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_HOST"));
        call(admin, true, operatorId, grants(depGrant(UUID.randomUUID(), "VIEW"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_DEPLOYMENT"));
        call(admin, true, operatorId, grants("{\"hostId\":\"%s\",\"deploymentId\":\"%s\",\"level\":\"VIEW\"}".formatted(host, dep)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GRANT"));
        call(admin, true, operatorId, grants("{\"level\":\"VIEW\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GRANT"));
        call(admin, true, operatorId, grants("{\"hostId\":\"%s\"}".formatted(host))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GRANT"));
        call(admin, true, operatorId, grants(hostGrant(host, "ROOT"))).andExpect(status().isBadRequest());
        call(admin, true, operatorId, grants(hostGrant(host, "VIEW"), hostGrant(host, "OPERATE")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUPLICATE_GRANT"));
        call(admin, true, operatorId, "{}").andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT level FROM permissions WHERE user_id = ?", String.class, operatorId))
                .isEqualTo("VIEW");
        assertThat(audit("FAILURE")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void viewerMayHoldOperateGrantButIsCappedByRole() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        call(admin, true, viewerId, grants(hostGrant(host, "OPERATE"))).andExpect(status().isOk());
    }
}
