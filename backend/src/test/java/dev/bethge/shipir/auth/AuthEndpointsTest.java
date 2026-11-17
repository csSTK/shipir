package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class AuthEndpointsTest {

    private static final String ADMIN_PASSWORD = "test-initial-admin-password";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private org.springframework.core.env.Environment env;

    private Cookie csrfCookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/me")).andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("XSRF-TOKEN cookie on first request").isNotNull();
        return cookie;
    }

    /**
     * Mimics a browser that sends its session cookie. Since Spring Framework 7.0.10 the mock request only reports a
     * valid requested session id when one was sent, and session-fixation protection only rotates then (as in a container).
     */
    private static RequestPostProcessor sendsSessionCookie(MockHttpSession session) {
        return request -> {
            request.setRequestedSessionId(session.getId());
            return request;
        };
    }

    private MvcResult login(String username, String password, MockHttpSession session, Cookie csrf) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .session(session)
                        .with(sendsSessionCookie(session))
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn();
    }

    @Test
    void meIsUnauthorizedJsonAndStillSetsCsrfCookie() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void sessionCookieIsHttpOnlyAndSameSiteLax() {
        assertThat(env.getProperty("server.servlet.session.cookie.http-only", Boolean.class)).isTrue();
        assertThat(env.getProperty("server.servlet.session.cookie.same-site")).isEqualToIgnoringCase("lax");
    }

    @Test
    void loginWithCorrectCredentialsReturnsUser() throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpSession session = new MockHttpSession();
        MvcResult result = login("admin", ADMIN_PASSWORD, session, csrf);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"username\":\"admin\"", "\"role\":\"ADMIN\"", "\"setupRequired\":true");
    }

    @Test
    void meWithLoginSessionReturnsSetupRequiredForSeededAdmin() throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpSession session = new MockHttpSession();
        MvcResult login = login("admin", ADMIN_PASSWORD, session, csrf);
        MockHttpSession authed = (MockHttpSession) login.getRequest().getSession(false);

        mvc.perform(get("/api/auth/me").session(authed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.setupRequired").value(true));
    }

    @Test
    void wrongPasswordAndUnknownUserGetIdentical401() throws Exception {
        Cookie csrf = csrfCookie();
        MvcResult wrong = login("admin", "wrong-password", new MockHttpSession(), csrf);
        MvcResult unknown = login("nobody", "wrong-password", new MockHttpSession(), csrf);

        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getContentAsString())
                .isNotBlank()
                .isEqualTo(unknown.getResponse().getContentAsString());
    }

    @Test
    void postWithoutCsrfHeaderIsForbidden() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postWithMismatchingCsrfHeaderIsForbidden() throws Exception {
        Cookie csrf = csrfCookie();
        mvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", "not-the-token")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sessionIdChangesOnLogin() throws Exception {
        Cookie csrf = csrfCookie();
        MockHttpSession before = new MockHttpSession();
        String idBefore = before.getId();
        MvcResult result = login("admin", ADMIN_PASSWORD, before, csrf);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getRequest().getSession(false).getId()).isNotEqualTo(idBefore);
    }

    @Test
    void invalidRequestBodyReturns400ErrorResponse() throws Exception {
        Cookie csrf = csrfCookie();
        mvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void logoutReturns204AndInvalidatesSession() throws Exception {
        Cookie csrf = csrfCookie();
        MvcResult login = login("admin", ADMIN_PASSWORD, new MockHttpSession(), csrf);
        MockHttpSession authed = (MockHttpSession) login.getRequest().getSession(false);

        mvc.perform(post("/api/auth/logout")
                        .session(authed)
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0));

        mvc.perform(get("/api/auth/me").session(authed))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedGetOnOtherApiPathIsJson401WithoutRedirect() throws Exception {
        mvc.perform(get("/api/containers"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists());
    }
}
