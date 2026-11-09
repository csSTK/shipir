package dev.bethge.shipir.compose;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared by the compose API tests: seeds one user per role with a known password and logs in through the
 * real endpoint, so requests carry the session and the CSRF cookie and header the SPA would send.
 */
public final class ComposeApiTestSupport {

    private final MockMvc mvc;
    private final String password;

    public ComposeApiTestSupport(MockMvc mvc, String password) {
        this.mvc = mvc;
        this.password = password;
    }

    /** Creates or updates the users admin, operator and viewer (one per role) with this support's password. */
    public void seedUsers(UserRepository users, PasswordEncoder encoder) {
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(password), Instant.now());
            users.save(u);
        }
    }

    /** The anonymous CSRF cookie, as the SPA gets it from {@code /api/auth/me}. */
    public Cookie anonymousCsrf() throws Exception {
        return mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    /** Logs in and returns the session plus the anonymous CSRF cookie that belongs to it. */
    public LoggedIn login(String username) throws Exception {
        Cookie anon = anonymousCsrf();
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new LoggedIn((MockHttpSession) r.getRequest().getSession(false), anon);
    }

    /** A logged-in session with its CSRF cookie. */
    public record LoggedIn(MockHttpSession session, Cookie csrf) {

        public MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder req) {
            return req.session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }
}
