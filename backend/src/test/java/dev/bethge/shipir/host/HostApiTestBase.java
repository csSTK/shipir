package dev.bethge.shipir.host;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.tls.TestPki;
import dev.bethge.shipir.docker.tls.TestPki.Issued;
import dev.bethge.shipir.docker.tls.TestPki.KeyType;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.DockerClientFactory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Shared setup: real Postgres, the test Docker daemon as LOCAL socket, one user per role with a changed password. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
abstract class HostApiTestBase {

    static final String PASSWORD = "A-test-Passw0rd-for-hosts!";

    @DynamicPropertySource
    static void dockerSocket(DynamicPropertyRegistry registry) {
        registry.add("shipir.docker.local-socket",
                () -> "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath());
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void resetHostsAndUsers() {
        jdbc.update("DELETE FROM docker_hosts");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
    }

    /** Logged-in session plus the CSRF cookie that belongs to it. */
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

    /** Throwaway PKI whose client key is valid PEM; the endpoint behind it does not exist. */
    static String[] pems() {
        Issued ca = TestPki.ca("host-test-ca", KeyType.EC);
        Issued client = TestPki.client(ca, "host-test-client", KeyType.EC);
        return new String[]{ca.certPem(), client.certPem(), client.keyPkcs8Pem()};
    }

    static String json(String s) {
        return s.replace('\'', '"');
    }

    static String tcpBody(String name, String host, int port, String[] pems) {
        return "{\"name\":\"%s\",\"connectionType\":\"TCP\",\"host\":\"%s\",\"port\":%d,\"tlsCaPem\":%s,\"tlsCertPem\":%s,\"tlsKeyPem\":%s}"
                .formatted(name, host, port, quote(pems[0]), quote(pems[1]), quote(pems[2]));
    }

    private static String quote(String pem) {
        return "\"" + pem.replace("\n", "\\n") + "\"";
    }
}
