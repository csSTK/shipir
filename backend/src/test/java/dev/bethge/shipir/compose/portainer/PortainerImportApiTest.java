package dev.bethge.shipir.compose.portainer;

import com.sun.net.httpserver.HttpServer;
import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Against a stub Portainer (JDK HttpServer). The test baseline allows loopback targets. */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class PortainerImportApiTest {

    static final String PASSWORD = "A-test-Passw0rd-for-portainer!";
    static final String TOKEN = "ptr_SECRETTOKEN1234567890";
    static final String ENV_SECRET = "hunter2-ENVSECRET";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;

    HttpServer stub;
    final List<String> seenKeys = new CopyOnWriteArrayList<>();
    volatile int authStatus = 200;
    volatile int oversize = 0;
    volatile boolean listMissing = false;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM audit_log");
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Portainer host', 'LOCAL', id FROM users WHERE username = 'admin'");
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", ex -> {
            seenKeys.add(String.valueOf(ex.getRequestHeaders().getFirst("X-API-Key")));
            String path = ex.getRequestURI().getPath();
            String body;
            int code = authStatus;
            if (code == 200 && listMissing && path.equals("/api/stacks")) {
                code = 404;
                body = "UPSTREAM-LEAK";
            } else if (code == 200) {
                switch (path) {
                    case "/api/stacks" -> body = "[{\"Id\":1,\"Name\":\"web\",\"Type\":2,\"EndpointId\":3},"
                            + "{\"Id\":2,\"Name\":\"swarmy\",\"Type\":1,\"EndpointId\":3},"
                            + "{\"Id\":4,\"Name\":\"kube\",\"Type\":3,\"EndpointId\":3}]";
                    case "/api/stacks/1" -> body = "{\"Id\":1,\"Name\":\"web\",\"Type\":2,\"Env\":"
                            + "[{\"name\":\"DB_PASSWORD\",\"value\":\"" + ENV_SECRET + "\"},{\"name\":\"MODE\",\"value\":\"prod\"}]}";
                    case "/api/stacks/1/file" -> body = oversize > 0
                            ? "{\"StackFileContent\":\"services: {}\"}" + " ".repeat(oversize)
                            : "{\"StackFileContent\":\"services:\\n  web:\\n    image: nginx\\n\"}";
                    case "/api/stacks/2" -> body = "{\"Id\":2,\"Name\":\"swarmy\",\"Type\":1,\"Env\":[]}";
                    default -> {
                        code = 404;
                        body = "UPSTREAM-LEAK";
                    }
                }
            } else {
                body = "UPSTREAM-LEAK";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        stub.start();
    }

    @AfterEach
    void tearDown() {
        stub.stop(0);
    }

    String url() {
        return "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    String body() {
        return "{\"url\":\"%s\",\"apiToken\":\"%s\"}".formatted(url(), TOKEN);
    }

    record Login(MockHttpSession session, Cookie csrf) {
    }

    Login login(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Login((MockHttpSession) r.getRequest().getSession(false), anon);
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(Login l, String path, String json) {
        return post(path).session(l.session()).cookie(l.csrf()).header("X-XSRF-TOKEN", l.csrf().getValue())
                .contentType(APPLICATION_JSON).content(json);
    }

    @Test
    void listMarksOnlyComposeStacksSupported() throws Exception {
        Login l = login("operator");
        mvc.perform(write(l, "/api/portainer-import/stacks", body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("web"))
                .andExpect(jsonPath("$[0].type").value("compose"))
                .andExpect(jsonPath("$[0].endpointId").value(3))
                .andExpect(jsonPath("$[0].supported").value(true))
                .andExpect(jsonPath("$[1].supported").value(false))
                .andExpect(jsonPath("$[1].reason").isNotEmpty())
                .andExpect(jsonPath("$[2].type").value("kubernetes"));
        assertThat(seenKeys).containsOnly(TOKEN);
    }

    @Test
    void fetchReturnsYamlAndEnv() throws Exception {
        Login l = login("admin");
        mvc.perform(write(l, "/api/portainer-import/stacks/1", body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("web"))
                .andExpect(jsonPath("$.yaml").value("services:\n  web:\n    image: nginx\n"))
                .andExpect(jsonPath("$.env.length()").value(2))
                .andExpect(jsonPath("$.env[0].key").value("DB_PASSWORD"))
                .andExpect(jsonPath("$.env[0].value").value(ENV_SECRET));
    }

    @Test
    void swarmStackIsRefused() throws Exception {
        Login l = login("admin");
        mvc.perform(write(l, "/api/portainer-import/stacks/2", body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_STACK"));
    }

    @Test
    void unknownStackIs404() throws Exception {
        Login l = login("admin");
        mvc.perform(write(l, "/api/portainer-import/stacks/99", body()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PORTAINER_STACK_NOT_FOUND"));
    }

    @Test
    void listCall404IsUnreachableNotStackNotFound() throws Exception {
        listMissing = true;
        Login l = login("admin");
        String res = mvc.perform(write(l, "/api/portainer-import/stacks", body()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PORTAINER_UNREACHABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(res).doesNotContain("PORTAINER_STACK_NOT_FOUND").doesNotContain("UPSTREAM-LEAK");
    }

    @Test
    void rejectedTokenIs422AndUpstreamBodyIsNotForwarded() throws Exception {
        authStatus = 401;
        Login l = login("admin");
        String res = mvc.perform(write(l, "/api/portainer-import/stacks", body()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PORTAINER_AUTH_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(res).doesNotContain("UPSTREAM-LEAK").doesNotContain(TOKEN);
    }

    @Test
    void upstreamServerErrorIs502WithoutBody() throws Exception {
        authStatus = 500;
        Login l = login("admin");
        String res = mvc.perform(write(l, "/api/portainer-import/stacks", body()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PORTAINER_UNREACHABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(res).doesNotContain("UPSTREAM-LEAK");
    }

    @Test
    void unreachablePortainerIs502() throws Exception {
        Login l = login("admin");
        String url = url();
        stub.stop(0);
        mvc.perform(write(l, "/api/portainer-import/stacks", "{\"url\":\"%s\",\"apiToken\":\"%s\"}".formatted(url, TOKEN)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PORTAINER_UNREACHABLE"));
        stub = HttpServer.create(); // so tearDown has something to stop
    }

    @Test
    void oversizeResponseIsRefused() throws Exception {
        oversize = 2_000_000;
        Login l = login("admin");
        mvc.perform(write(l, "/api/portainer-import/stacks/1", body()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PORTAINER_BAD_RESPONSE"));
    }

    @Test
    void viewerIsForbiddenAndOperatorAllowed() throws Exception {
        Login v = login("viewer");
        mvc.perform(write(v, "/api/portainer-import/stacks", body())).andExpect(status().isForbidden());
        mvc.perform(write(v, "/api/portainer-import/stacks/1", body())).andExpect(status().isForbidden());
        Login o = login("operator");
        mvc.perform(write(o, "/api/portainer-import/stacks/1", body())).andExpect(status().isOk());
        assertThat(seenKeys).hasSize(2); // only the operator's fetch reached the stub (stack + file)
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(post("/api/portainer-import/stacks").contentType(APPLICATION_JSON).content(body()))
                .andExpect(status().is4xxClientError());
        assertThat(seenKeys).isEmpty();
    }

    @Test
    void tokenAndEnvValuesAppearNeitherInLogsErrorsNorAudit(CapturedOutput output) throws Exception {
        Login l = login("admin");
        mvc.perform(write(l, "/api/portainer-import/stacks/1", body())).andExpect(status().isOk());
        String bad = mvc.perform(write(l, "/api/portainer-import/stacks/1",
                        "{\"url\":\"ftp://x\",\"apiToken\":\"%s\"}".formatted(TOKEN)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        String blank = mvc.perform(write(l, "/api/portainer-import/stacks/1",
                        "{\"url\":\"%s\",\"apiToken\":\"\"}".formatted(url())))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        authStatus = 401;
        String auth = mvc.perform(write(l, "/api/portainer-import/stacks/1", body()))
                .andReturn().getResponse().getContentAsString();
        assertThat(bad + blank + auth).doesNotContain(TOKEN).doesNotContain(ENV_SECRET);

        String audit = jdbc.queryForList("SELECT action || ' ' || coalesce(target_type,'') || ' ' || coalesce(details::text,'') "
                + "FROM audit_log WHERE action = 'PORTAINER_IMPORT_FETCH'", String.class).toString();
        assertThat(audit).contains("PORTAINER_IMPORT_FETCH").contains("web").contains("127.0.0.1")
                .doesNotContain(TOKEN).doesNotContain(ENV_SECRET).doesNotContain("DB_PASSWORD");
        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain(ENV_SECRET);
    }

    @Test
    void malformedUrlWithCredentialsIsNeverLoggedOrAudited(CapturedOutput output) throws Exception {
        Login l = login("admin");
        String bad = "http://user:pw-URLSECRET@exa mple.com/x";
        for (String path : List.of("/api/portainer-import/stacks", "/api/portainer-import/stacks/1")) {
            String res = mvc.perform(write(l, path, "{\"url\":\"%s\",\"apiToken\":\"%s\"}".formatted(bad, TOKEN)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_URL"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(res).doesNotContain("URLSECRET");
        }
        String audit = jdbc.queryForList("SELECT coalesce(target_type,'') || ' ' || coalesce(details::text,'') FROM audit_log "
                + "WHERE action = 'PORTAINER_IMPORT_FETCH'", String.class).toString();
        assertThat(audit).doesNotContain("URLSECRET").doesNotContain("exa mple");
        assertThat(output.getAll()).doesNotContain("URLSECRET").doesNotContain(TOKEN);
    }

    @Test
    void redirectsAreNotFollowedSoTheTokenNeverLeaves() throws Exception {
        List<String> hits = new CopyOnWriteArrayList<>();
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", ex -> {
            hits.add(String.valueOf(ex.getRequestHeaders().getFirst("X-API-Key")));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        other.start();
        HttpServer redirector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirector.createContext("/", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + other.getAddress().getPort() + "/api/stacks");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        redirector.start();
        try {
            Login l = login("admin");
            mvc.perform(write(l, "/api/portainer-import/stacks", "{\"url\":\"http://127.0.0.1:%d\",\"apiToken\":\"%s\"}"
                            .formatted(redirector.getAddress().getPort(), TOKEN)))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("PORTAINER_UNREACHABLE"));
            assertThat(hits).isEmpty();
        } finally {
            redirector.stop(0);
            other.stop(0);
        }
    }
}
