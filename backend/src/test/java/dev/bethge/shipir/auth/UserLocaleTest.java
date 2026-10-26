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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The per-user UI language: stored on the user, exposed by /api/auth/me, changed by PUT /api/auth/me/locale. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserLocaleTest {

    private static final String ADMIN_PASSWORD = "test-initial-admin-password";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;

    @BeforeEach
    void resetAdmin() {
        // A VIEWER has no setup step beyond the password (an ADMIN would be gated until a host exists).
        jdbc.update("DELETE FROM users WHERE username = 'viewer'");
        jdbc.update("INSERT INTO users (username, password_hash, role, password_changed_at) VALUES ('viewer', ?, 'VIEWER', now())",
                encoder.encode(ADMIN_PASSWORD));
    }

    private Cookie csrfCookie() throws Exception {
        return mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private MockHttpSession loginViewer(Cookie csrf) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login")
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"viewer\",\"password\":\"%s\"}".formatted(ADMIN_PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private MvcResult putLocale(MockHttpSession session, Cookie csrf, String body) throws Exception {
        return mvc.perform(put("/api/auth/me/locale").session(session)
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON).content(body)).andReturn();
    }

    @Test
    void meExposesNoLocaleUntilOneIsChosen() throws Exception {
        MockHttpSession session = loginViewer(csrfCookie());
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value((Object) null));
    }

    @Test
    void putLocaleStoresSupportedLocaleAndMeReturnsIt() throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpSession session = loginViewer(csrf);

        assertThat(putLocale(session, csrf, "{\"locale\":\"de\"}").getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT locale FROM users WHERE username = 'viewer'", String.class)).isEqualTo("de");
        mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.locale").value("de"));
    }

    @Test
    void putLocaleNullClearsTheChoice() throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpSession session = loginViewer(csrf);
        putLocale(session, csrf, "{\"locale\":\"de\"}");

        assertThat(putLocale(session, csrf, "{\"locale\":null}").getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT locale FROM users WHERE username = 'viewer'", String.class)).isNull();
    }

    @Test
    void unsupportedLocaleIs400AndChangesNothing() throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpSession session = loginViewer(csrf);
        putLocale(session, csrf, "{\"locale\":\"de\"}");

        for (String bad : new String[]{"fr", "DE", "de-DE", "", "'; DROP TABLE users;--"}) {
            assertThat(putLocale(session, csrf, "{\"locale\":\"%s\"}".formatted(bad.replace("'", "\\u0027")))
                    .getResponse().getStatus()).as(bad).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT locale FROM users WHERE username = 'viewer'", String.class)).isEqualTo("de");
    }

    @Test
    void putWithoutCsrfTokenIs403AndChangesNothing() throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpSession session = loginViewer(csrf);
        // Same request with a valid token succeeds, so the 403 below is really the missing token.
        assertThat(putLocale(session, csrf, "{\"locale\":\"en\"}").getResponse().getStatus()).isEqualTo(200);

        mvc.perform(put("/api/auth/me/locale").session(session)
                        .contentType(APPLICATION_JSON).content("{\"locale\":\"de\"}"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT locale FROM users WHERE username = 'viewer'", String.class)).isEqualTo("en");
    }

    @Test
    void unauthenticatedPutIs401() throws Exception {
        Cookie csrf = csrfCookie();
        mvc.perform(put("/api/auth/me/locale")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON).content("{\"locale\":\"de\"}"))
                .andExpect(status().isUnauthorized());
    }
}
