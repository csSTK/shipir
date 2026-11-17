package dev.bethge.shipir.git;

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

/** Real Postgres, one user per role, encryption key configured, ADMIN has an environment (setup gate). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
abstract class GitTestBase {

    static final String PASSWORD = "A-test-Passw0rd-for-git-creds!";
    static final String TOKEN = "ghp_SECRETTOKEN1234567890abcdef";

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
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM git_credentials");
        jdbc.update("DELETE FROM audit_log");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Git host', 'LOCAL', id FROM users WHERE username = 'admin'");
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

    /** Credentials are bound to a host; the stubs of these tests listen on 127.0.0.1. */
    static final String STUB_HOST = "127.0.0.1";

    static String json(String name, String username, String token) {
        return json(name, STUB_HOST, username, token);
    }

    static String json(String name, String host, String username, String token) {
        return token == null
                ? "{\"name\":\"%s\",\"host\":\"%s\",\"username\":\"%s\"}".formatted(name, host, username)
                : "{\"name\":\"%s\",\"host\":\"%s\",\"username\":\"%s\",\"token\":\"%s\"}"
                        .formatted(name, host, username, token);
    }

    String create(Login l, String name) throws Exception {
        MvcResult r = mvc.perform(l.write(post("/api/git/credentials")).contentType(APPLICATION_JSON)
                .content(json(name, "octocat", TOKEN))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    /** Minimal deployment row referencing a credential (columns from V3/V13). */
    void insertDeploymentUsing(String credentialId) {
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by, source_type, git_repo_url, "
                        + "git_ref, git_compose_path, git_credential_id) "
                        + "SELECT 'git-deployment', h.id, 'x/compose.yaml', u.id, 'GIT', 'https://example.com/r.git', "
                        + "'main', 'compose.yaml', ?::uuid FROM docker_hosts h, users u WHERE u.username = 'admin' LIMIT 1",
                credentialId);
    }

    UUID userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", UUID.class, username);
    }
}
