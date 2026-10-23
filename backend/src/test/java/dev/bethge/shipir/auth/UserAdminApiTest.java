package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mutates users, so it has its own context/database. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class UserAdminApiTest {

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

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("DELETE FROM users WHERE username <> 'admin'");
        // an environment must exist, otherwise the setup gate blocks the admin's ENVIRONMENT step
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'base', 'LOCAL', id FROM users WHERE username = 'admin'");
        jdbc.update("UPDATE users SET password_hash = ?, password_changed_at = now(), disabled_at = NULL, role = 'ADMIN' "
                + "WHERE username = 'admin'", encoder.encode(ADMIN_PASSWORD));
    }

    private record Session(MockHttpSession session, Cookie csrf) {
    }

    private Cookie csrf() throws Exception {
        return mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private MvcResult tryLogin(String user, String password) throws Exception {
        Cookie c = csrf();
        return mvc.perform(post("/api/auth/login").cookie(c).header("X-XSRF-TOKEN", c.getValue())
                .contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", user, "password", password)))).andReturn();
    }

    private Session login(String user, String password) throws Exception {
        Cookie c = csrf();
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(c).header("X-XSRF-TOKEN", c.getValue())
                .contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", user, "password", password)))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Session((MockHttpSession) r.getRequest().getSession(false), c);
    }

    private Session admin() throws Exception {
        return login("admin", ADMIN_PASSWORD);
    }

    private JsonNode create(Session s, String username, String role) throws Exception {
        MvcResult r = mvc.perform(post("/api/users").session(s.session).cookie(s.csrf)
                        .header("X-XSRF-TOKEN", s.csrf.getValue()).contentType(APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"role\":\"%s\"}".formatted(username, role)))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions call(Session s, String method, String url, String body)
            throws Exception {
        var b = switch (method) {
            case "GET" -> get(url);
            case "PUT" -> put(url);
            case "DELETE" -> delete(url);
            default -> post(url);
        };
        b.session(s.session).cookie(s.csrf).header("X-XSRF-TOKEN", s.csrf.getValue());
        if (body != null) {
            b.contentType(APPLICATION_JSON).content(body);
        }
        return mvc.perform(b);
    }

    /** Creates an ADMIN-set user, sets their password to a known one so they can log in fully. */
    private Session activeUser(Session admin, String name, String role) throws Exception {
        create(admin, name, role);
        jdbc.update("UPDATE users SET password_hash = ?, password_changed_at = now() WHERE username = ?",
                encoder.encode(PW), name);
        return login(name, PW);
    }

    private UUID idOf(String name) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", UUID.class, name);
    }

    private long auditCount(String action, String outcome) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND outcome = ?",
                Long.class, action, outcome);
    }

    @Test
    void nonAdminsGet403OnEveryEndpointAndCsrfIsRequired() throws Exception {
        Session admin = admin();
        Session op = activeUser(admin, "oper", "OPERATOR");
        Session viewer = activeUser(admin, "viewer1", "VIEWER");
        String id = idOf("viewer1").toString();
        for (Session s : List.of(op, viewer)) {
            call(s, "GET", "/api/users", null).andExpect(status().isForbidden());
            call(s, "POST", "/api/users", "{\"username\":\"x1x\",\"role\":\"VIEWER\"}").andExpect(status().isForbidden());
            call(s, "PUT", "/api/users/" + id + "/role", "{\"role\":\"ADMIN\"}").andExpect(status().isForbidden());
            call(s, "POST", "/api/users/" + id + "/disable", null).andExpect(status().isForbidden());
            call(s, "POST", "/api/users/" + id + "/enable", null).andExpect(status().isForbidden());
            call(s, "POST", "/api/users/" + id + "/reset-password", null).andExpect(status().isForbidden());
            call(s, "DELETE", "/api/users/" + id, null).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        // no CSRF header
        mvc.perform(post("/api/users").session(admin.session).cookie(admin.csrf).contentType(APPLICATION_JSON)
                        .content("{\"username\":\"nocsrf\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createReturnsOneTimePasswordOnceAndForcesPasswordStep(CapturedOutput output) throws Exception {
        Session admin = admin();
        JsonNode created = create(admin, "newbie", "OPERATOR");
        String password = created.get("oneTimePassword").asString();
        assertThat(password).hasSizeGreaterThanOrEqualTo(15);
        assertThat(created.get("role").asString()).isEqualTo("OPERATOR");

        MvcResult login = tryLogin("newbie", password);
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        MockHttpSession s = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.setupStep").value("PASSWORD"));
        mvc.perform(get("/api/hosts").session(s)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SETUP_REQUIRED"));

        // list never exposes the password
        String list = call(admin, "GET", "/api/users", null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username=='newbie')].passwordChangeRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(password).doesNotContain("passwordHash");

        assertThat(output.getAll()).doesNotContain(password);
        assertThat(jdbc.queryForList("SELECT action, target_id, details::text AS d FROM audit_log").toString())
                .doesNotContain(password);
        assertThat(auditCount("USER_CREATE", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void createRejectsDuplicateAndInvalidUsername() throws Exception {
        Session admin = admin();
        create(admin, "dupe", "VIEWER");
        call(admin, "POST", "/api/users", "{\"username\":\"dupe\",\"role\":\"VIEWER\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        call(admin, "POST", "/api/users", "{\"username\":\"a b\",\"role\":\"VIEWER\"}")
                .andExpect(status().isBadRequest());
        call(admin, "POST", "/api/users", "{\"username\":\"fine\",\"role\":\"ROOT\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void roleChangeUpdatesRoleEndsSessionsAndIsAudited() throws Exception {
        Session admin = admin();
        Session op = activeUser(admin, "rolechg", "OPERATOR");
        call(admin, "PUT", "/api/users/" + idOf("rolechg") + "/role", "{\"role\":\"VIEWER\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("VIEWER"));
        assertThat(auditCount("USER_ROLE_CHANGE", "SUCCESS")).isEqualTo(1);
        call(op, "GET", "/api/auth/me", null).andExpect(status().isUnauthorized());
        call(admin, "PUT", "/api/users/" + UUID.randomUUID() + "/role", "{\"role\":\"VIEWER\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void disableBlocksLoginSameBodyAsWrongPasswordEndsSessionAndEnableRestores() throws Exception {
        Session admin = admin();
        Session victim = activeUser(admin, "victim", "VIEWER");
        call(victim, "GET", "/api/auth/me", null).andExpect(status().isOk());

        call(admin, "POST", "/api/users/" + idOf("victim") + "/disable", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.disabled").value(true));

        call(victim, "GET", "/api/auth/me", null).andExpect(status().isUnauthorized());
        MvcResult disabledLogin = tryLogin("victim", PW);
        MvcResult wrongLogin = tryLogin("victim", "totally-wrong-Passw0rd!");
        assertThat(disabledLogin.getResponse().getStatus()).isEqualTo(401);
        assertThat(disabledLogin.getResponse().getContentAsString())
                .isEqualTo(wrongLogin.getResponse().getContentAsString());

        call(admin, "POST", "/api/users/" + idOf("victim") + "/enable", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.disabled").value(false));
        assertThat(tryLogin("victim", PW).getResponse().getStatus()).isEqualTo(200);
        assertThat(auditCount("USER_DISABLE", "SUCCESS")).isEqualTo(1);
        assertThat(auditCount("USER_ENABLE", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void resetPasswordIssuesNewOneTimePasswordEndsSessionsAndForcesSetup(CapturedOutput output) throws Exception {
        Session admin = admin();
        Session victim = activeUser(admin, "resetme", "OPERATOR");
        String password = json.readTree(call(admin, "POST", "/api/users/" + idOf("resetme") + "/reset-password", null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("oneTimePassword").asString();

        call(victim, "GET", "/api/auth/me", null).andExpect(status().isUnauthorized());
        assertThat(tryLogin("resetme", PW).getResponse().getStatus()).isEqualTo(401);
        MvcResult again = tryLogin("resetme", password);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/auth/me").session((MockHttpSession) again.getRequest().getSession(false)))
                .andExpect(jsonPath("$.setupStep").value("PASSWORD"));
        assertThat(output.getAll()).doesNotContain(password);
        assertThat(jdbc.queryForList("SELECT * FROM audit_log").toString()).doesNotContain(password);
        assertThat(auditCount("USER_PASSWORD_RESET", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void deleteRemovesUserAndEndsSessions() throws Exception {
        Session admin = admin();
        Session victim = activeUser(admin, "gone", "VIEWER");
        call(admin, "DELETE", "/api/users/" + idOf("gone"), null).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'gone'", Long.class)).isZero();
        call(victim, "GET", "/api/auth/me", null).andExpect(status().isUnauthorized());
        assertThat(auditCount("USER_DELETE", "SUCCESS")).isEqualTo(1);
        call(admin, "DELETE", "/api/users/" + UUID.randomUUID(), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void deleteOfUserWithHistoryIsConflict() throws Exception {
        Session admin = admin();
        create(admin, "owner", "ADMIN");
        UUID owner = idOf("owner");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) VALUES ('h1', 'LOCAL', ?)", owner);
        call(admin, "DELETE", "/api/users/" + owner, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_HAS_HISTORY"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'owner'", Long.class)).isOne();
        assertThat(auditCount("USER_DELETE", "FAILURE")).isEqualTo(1);
    }

    @Test
    void selfActionsAreRejected() throws Exception {
        Session admin = admin();
        String self = idOf("admin").toString();
        call(admin, "POST", "/api/users/" + self + "/disable", null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SELF_DISABLE"));
        call(admin, "PUT", "/api/users/" + self + "/role", "{\"role\":\"VIEWER\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SELF_DEMOTE"));
        call(admin, "DELETE", "/api/users/" + self, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SELF_DELETE"));
        call(admin, "POST", "/api/users/" + self + "/reset-password", null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SELF_RESET_PASSWORD"));
        call(admin, "GET", "/api/auth/me", null).andExpect(status().isOk());
    }

    @Test
    void lastEnabledAdminCannotBeDisabledDemotedOrDeleted() throws Exception {
        Session admin = admin();
        create(admin, "second", "ADMIN");
        // the acting admin loses their enabled flag while the session is still alive
        jdbc.update("UPDATE users SET disabled_at = now() WHERE username = 'admin'");
        String target = idOf("second").toString();
        call(admin, "POST", "/api/users/" + target + "/disable", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        call(admin, "PUT", "/api/users/" + target + "/role", "{\"role\":\"VIEWER\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        call(admin, "DELETE", "/api/users/" + target, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
    }

    @Test
    void failedActionsAreAuditedAsFailure() throws Exception {
        Session admin = admin();
        call(admin, "POST", "/api/users/" + idOf("admin") + "/disable", null).andExpect(status().isBadRequest());
        assertThat(auditCount("USER_DISABLE", "FAILURE")).isEqualTo(1);
    }
}
