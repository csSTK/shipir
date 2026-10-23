package dev.bethge.shipir.auth;

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

import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mutates the seeded admin, so it gets its own context/database and resets the row before each test. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordChangeTest {

    private static final String ADMIN_PASSWORD = "test-initial-admin-password";
    private static final String NEW_PASSWORD = "A-brand-new-Passw0rd!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;

    @BeforeEach
    void resetAdmin() {
        jdbc.update("UPDATE users SET password_hash = ?, password_changed_at = NULL WHERE username = 'admin'",
                encoder.encode(ADMIN_PASSWORD));
    }

    private Cookie csrfCookie() throws Exception {
        return mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private MockHttpSession loginAdmin(String password, Cookie csrf) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login")
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"%s\"}".formatted(password))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private MvcResult changePassword(MockHttpSession session, Cookie csrf, String current, String next)
            throws Exception {
        return mvc.perform(post("/api/auth/password").session(session)
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)))
                .andReturn();
    }

    private Timestamp passwordChangedAt() {
        return jdbc.queryForObject("SELECT password_changed_at FROM users WHERE username = 'admin'",
                Timestamp.class);
    }

    @Test
    void gatedEndpointIsForbiddenWithSetupRequiredWhileMeLogoutAndPasswordStayAllowed() throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpSession session = loginAdmin(ADMIN_PASSWORD, csrf);

        mvc.perform(get("/api/containers").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SETUP_REQUIRED"));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        // reaches the controller (400 validation), i.e. not blocked by the gate
        mvc.perform(post("/api/auth/password").session(session)
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/logout").session(session)
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    void unauthenticatedPasswordChangeIs401() throws Exception {
        Cookie csrf = csrfCookie();
        mvc.perform(post("/api/auth/password")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"y\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCurrentPasswordIs400AndChangesNothing() throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpSession session = loginAdmin(ADMIN_PASSWORD, csrf);

        MvcResult r = changePassword(session, csrf, "wrong-current-password", NEW_PASSWORD);

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("\"code\":\"INVALID_CURRENT_PASSWORD\"");
        assertThat(passwordChangedAt()).isNull();
        loginAdmin(ADMIN_PASSWORD, csrf);
    }

    @Test
    void policyViolationIs400WithViolationsListAndChangesNothing() throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpSession session = loginAdmin(ADMIN_PASSWORD, csrf);

        mvc.perform(post("/api/auth/password").session(session)
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"short\"}".formatted(ADMIN_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.violations").isArray())
                .andExpect(jsonPath("$.violations[?(@ == 'TOO_SHORT')]").exists())
                .andExpect(jsonPath("$.violations[?(@ == 'MISSING_UPPERCASE')]").exists());
        assertThat(passwordChangedAt()).isNull();
    }

    @Test
    void newPasswordEqualToCurrentIsRejected() throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpSession session = loginAdmin(ADMIN_PASSWORD, csrf);
        // current password is lowercase-only, so other violations appear too; SAME_AS_CURRENT must be listed
        MvcResult r = changePassword(session, csrf, ADMIN_PASSWORD, ADMIN_PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("SAME_AS_CURRENT");
    }

    @Test
    void validChangeLiftsGateRotatesSessionAndSwitchesPassword() throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpSession session = loginAdmin(ADMIN_PASSWORD, csrf);
        String idBefore = session.getId();

        MvcResult r = changePassword(session, csrf, ADMIN_PASSWORD, NEW_PASSWORD);

        assertThat(r.getResponse().getStatus()).isEqualTo(204);
        assertThat(r.getResponse().getContentAsString()).doesNotContain(NEW_PASSWORD);
        MockHttpSession rotated = (MockHttpSession) r.getRequest().getSession(false);
        assertThat(rotated.getId()).isNotEqualTo(idBefore);
        assertThat(passwordChangedAt()).isNotNull();

        mvc.perform(get("/api/auth/me").session(rotated))
                .andExpect(status().isOk())
                // the admin has no environment yet, so setup continues with the ENVIRONMENT step
                .andExpect(jsonPath("$.setupRequired").value(true))
                .andExpect(jsonPath("$.setupStep").value("ENVIRONMENT"));
        // password gate lifted: the environment step's endpoint is reachable (400 validation, not 403)
        mvc.perform(post("/api/hosts/connection-test").session(rotated)
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        Cookie freshCsrf = csrfCookie();
        MvcResult oldLogin = mvc.perform(post("/api/auth/login")
                .cookie(freshCsrf).header("X-XSRF-TOKEN", freshCsrf.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"%s\"}".formatted(ADMIN_PASSWORD))).andReturn();
        assertThat(oldLogin.getResponse().getStatus()).isEqualTo(401);
        loginAdmin(NEW_PASSWORD, freshCsrf);
    }
}
