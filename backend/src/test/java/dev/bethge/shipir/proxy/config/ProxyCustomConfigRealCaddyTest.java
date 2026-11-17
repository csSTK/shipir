package dev.bethge.shipir.proxy.config;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.client.ContainerService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.DockerClientFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The custom Caddy file against a real Caddy: a throw-away container that carries the Compose labels of the managed
 * proxy (no ports published, nothing else touched) runs Caddy with a Caddyfile that imports {@code custom.caddy}.
 * Skipped without a Docker daemon or the pinned Caddy image locally (nothing is pulled), and when a
 * {@code shipir-proxy} deployment already exists on the daemon.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ProxyCustomConfigRealCaddyTest {

    static final String PASSWORD = "A-test-Passw0rd-for-proxies!";
    static final String IMAGE = "caddy:2.10.2-alpine";
    static final String NAME = "shipir-custom-config-test-" + UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("shipir.docker.local-socket", () -> "unix://"
                + DockerClientFactory.instance().getRemoteDockerUnixSocketPath());
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    ContainerService containers;
    @Autowired
    ContainerFileService fileService;
    @Autowired
    DockerHostConnectionManager connections;

    @Test
    void caddyItselfRejectsABrokenCustomFileAndAcceptsAGoodOne() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon available");
        assumeTrue(run("docker", "image", "inspect", IMAGE) == 0, IMAGE + " not available locally");

        User admin = users.findByUsername("admin").orElseThrow();
        admin.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
        users.save(admin);
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Real', 'LOCAL', id FROM users WHERE username = 'admin'");
        UUID hostId = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        DockerEndpoint endpoint = connections.localEndpoint();
        assumeTrue(containers.listContainers(hostId, endpoint).stream()
                        .noneMatch(c -> "shipir-proxy".equals(c.composeProject())),
                "another shipir-proxy deployment exists on this daemon");
        jdbc.update("INSERT INTO proxy_instances (host_id, type, default_cert_mode, acme_email) VALUES (?, 'CADDY', 'INTERNAL', 'ops@example.com')",
                hostId);

        try {
            assertThat(run("docker", "run", "-d", "--name", NAME,
                    "--label", "com.docker.compose.project=shipir-proxy", "--label", "com.docker.compose.service=caddy",
                    IMAGE, "sh", "-c",
                    "mkdir -p /etc/shipir-proxy && touch /etc/shipir-proxy/custom.caddy && "
                            + "printf '{\\n\\tadmin localhost:2019\\n}\\nimport custom.caddy\\n' > /etc/shipir-proxy/Caddyfile && "
                            + "exec caddy run --config /etc/shipir-proxy/Caddyfile --adapter caddyfile")).isZero();
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                    assertThat(run("sh", "-c", "docker logs " + NAME + " 2>&1 | grep -q 'serving initial configuration'")).isZero());

            MockHttpSession session = null;
            Cookie csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
            MvcResult login = mvc.perform(post("/api/auth/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(APPLICATION_JSON)
                    .content("{\"username\":\"admin\",\"password\":\"%s\"}".formatted(PASSWORD))).andReturn();
            session = (MockHttpSession) login.getRequest().getSession(false);
            String url = "/api/hosts/" + hostId + "/proxy/config/custom";

            mvc.perform(put(url).session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                            .contentType(APPLICATION_JSON).content("{\"content\":\"this_is_not_a_directive {\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PROXY_CONFIG"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Caddy rejected the configuration")));
            assertThat(custom(hostId, endpoint)).as("nothing written to the proxy").isEmpty();
            assertThat(run("docker", "exec", NAME, "sh", "-c", "ls /etc/shipir-proxy | grep -q validate-"))
                    .as("validation copy removed").isNotZero();
            assertThat(jdbc.queryForObject("SELECT custom_config FROM proxy_instances", String.class)).isEmpty();

            // Caddy echoes the offending token; a token that looks like our exit marker must not fake success
            mvc.perform(put(url).session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                            .contentType(APPLICATION_JSON).content("{\"content\":\":8082 {\\n\\tshipir-exit:0\\n}\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PROXY_CONFIG"));
            assertThat(custom(hostId, endpoint)).isEmpty();

            mvc.perform(put(url).session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                            .contentType(APPLICATION_JSON).content("{\"content\":\":8081 {\\n\\trespond /shipir-ping 200\\n}\"}"))
                    .andExpect(status().isOk());
            assertThat(custom(hostId, endpoint)).isEqualTo(":8081 {\n\trespond /shipir-ping 200\n}");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM proxy_custom_config_versions", Integer.class)).isEqualTo(1);
        } finally {
            run("docker", "rm", "-f", NAME);
        }
    }

    private String custom(UUID hostId, DockerEndpoint endpoint) {
        String id = containers.listContainers(hostId, endpoint).stream().filter(c -> NAME.equals(c.name().replaceFirst("^/", "")))
                .findFirst().orElseThrow().id();
        return new String(fileService.readFile(hostId, endpoint, id, "/etc/shipir-proxy/custom.caddy", 100_000), StandardCharsets.UTF_8);
    }

    private static int run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return p.exitValue();
    }
}
