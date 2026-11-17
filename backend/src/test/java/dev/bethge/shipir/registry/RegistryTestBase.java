package dev.bethge.shipir.registry;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.SetupGateFixture;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real Postgres, one user per role, encryption key configured, ADMIN has an environment (setup gate). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
abstract class RegistryTestBase {

    static final String PASSWORD = "A-test-Passw0rd-for-registries!";
    static final String SECRET = "dckr_pat_SECRETVALUE1234567890";

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
        jdbc.update("DELETE FROM registry_credentials");
        jdbc.update("DELETE FROM audit_log");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        SetupGateFixture.resetWithAdminHost(jdbc, "Registry host");
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

    static String json(String name, String host, String username, String secret) {
        String s = secret == null ? "" : ",\"secret\":\"%s\"".formatted(secret);
        return "{\"name\":\"%s\",\"registryHost\":\"%s\",\"username\":\"%s\"%s}".formatted(name, host, username, s);
    }

    String create(Login l, String name, String host) throws Exception {
        MvcResult r = mvc.perform(l.write(post("/api/registries")).contentType(APPLICATION_JSON)
                .content(json(name, host, "robot", SECRET))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }
}
