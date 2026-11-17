package dev.bethge.shipir.proxy;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.ContainerDto;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>Opt-in</b>: run with {@code -Dshipir.test.real-proxy=true}. It publishes ports 80, 443 and 443/udp on the real
 * local daemon and uses the fixed names the product uses ({@code shipir-proxy} project, network and volumes), so
 * it also refuses to run when any of those already exist and removes only what it created itself.
 *
 * <p>Sets up a real Caddy proxy through the API against the local Docker daemon: compose up, config pushed into the
 * running container, reload accepted and rejected, teardown. Skipped without Docker, the compose CLI, the pinned Caddy
 * image locally (nothing is pulled) or when another {@code shipir-proxy} deployment or a user of ports 80/443 exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ProxyRealDockerTest {

    static final String PASSWORD = "A-test-Passw0rd-for-proxies!";
    static final String IMAGE = "caddy:2.10.2-alpine";
    static final Path BASE_DIR = baseDir();

    private static Path baseDir() {
        try {
            return Files.createTempDirectory("shipir-proxy-real-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("shipir.stacks.base-dir", BASE_DIR::toString);
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
    @Autowired
    ProxySyncService sync;
    @Autowired
    ProxyInstanceRepository instances;

    @Test
    void caddyIsDeployedConfiguredAndReloadedOnARealDaemon() throws Exception {
        assumeTrue(Boolean.getBoolean("shipir.test.real-proxy"), "opt-in: -Dshipir.test.real-proxy=true");
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon available");
        assumeTrue(run("docker", "compose", "version") == 0, "docker compose CLI not available");
        assumeTrue(run("docker", "image", "inspect", IMAGE) == 0, IMAGE + " not available locally");

        assumeTrue(run("docker", "network", "inspect", "shipir-proxy") != 0, "network shipir-proxy already exists");
        assumeTrue(run("docker", "volume", "inspect", "shipir-proxy_shipir-proxy-config") != 0
                        && run("docker", "volume", "inspect", "shipir-proxy_shipir-proxy-data") != 0,
                "shipir-proxy volumes already exist");

        User admin = users.findByUsername("admin").orElseThrow();
        admin.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
        users.save(admin);
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Real', 'LOCAL', id FROM users WHERE username = 'admin'");
        UUID hostId = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        DockerEndpoint endpoint = connections.localEndpoint();
        assumeTrue(containers.listContainers(hostId, endpoint).stream()
                        .noneMatch(c -> ProxySyncService.PROXY_DEPLOYMENT.equals(c.composeProject())),
                "another shipir-proxy deployment exists on this daemon");

        MockHttpSession session = null;
        Cookie csrf = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult login = mvc.perform(post("/api/auth/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"%s\"}".formatted(PASSWORD))).andReturn();
        session = (MockHttpSession) login.getRequest().getSession(false);
        String url = "/api/hosts/" + hostId + "/proxy/managed";
        String blocked = mvc.perform(get(url).session(session)).andReturn().getResponse().getContentAsString();
        assumeTrue(blocked.contains("\"setupBlockedReason\":null"), "ports 80/443 are not free: " + blocked);

        try {
            mvc.perform(post(url).session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                            .contentType(APPLICATION_JSON)
                            .content("{\"type\":\"CADDY\",\"acmeEmail\":\"ops@example.com\",\"defaultCertMode\":\"INTERNAL\"}"))
                    .andExpect(status().isCreated());

            // the container ends up running with the generated Caddyfile in place
            await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions().untilAsserted(() -> {
                ContainerDto proxy = proxyContainer(hostId, endpoint);
                assertThat(proxy.state()).isEqualTo("running");
                String caddyfile = new String(fileService.readFile(hostId, endpoint, proxy.id(),
                        "/etc/shipir-proxy/Caddyfile", 1_000_000), StandardCharsets.UTF_8);
                assertThat(caddyfile).contains("ops@example.com").contains("import custom.caddy");
            });
            ContainerDto proxy = proxyContainer(hostId, endpoint);
            assertThat(fileService.readFile(hostId, endpoint, proxy.id(), "/etc/shipir-proxy/custom.caddy", 1000)).isEmpty();

            // a valid sync (reload) succeeds, a broken custom file is reported with Caddy's own message
            sync.sync(hostId);
            ProxyInstance instance = instances.findByHostId(hostId).orElseThrow();
            instance.setCustomConfig("this_is_not_a_directive {");
            instances.save(instance);
            assertThatThrownBy(() -> sync.sync(hostId)).isInstanceOfSatisfying(ProxyException.class, e -> {
                assertThat(e.code()).isEqualTo("PROXY_SYNC_FAILED");
                assertThat(e.getMessage()).contains("Caddy rejected the configuration");
            });
            instance.setCustomConfig("");
            instances.save(instance);
            sync.sync(hostId);
        } finally {
            mvc.perform(delete(url).session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())).andReturn();
            // the config volume is deleted by the removal itself; the ACME data volume and the network are ours to clean
            run("docker", "volume", "rm", "shipir-proxy_shipir-proxy-data");
            run("docker", "network", "rm", "shipir-proxy");
        }
        assertThat(run("docker", "volume", "inspect", "shipir-proxy_shipir-proxy-config")).as("config volume removed").isNotZero();
        assertThat(containers.listContainers(hostId, endpoint).stream()
                .filter(c -> ProxySyncService.PROXY_DEPLOYMENT.equals(c.composeProject())).toList()).isEmpty();
    }

    private ContainerDto proxyContainer(UUID hostId, DockerEndpoint endpoint) {
        List<ContainerDto> all = containers.listContainers(hostId, endpoint).stream()
                .filter(c -> ProxySyncService.PROXY_DEPLOYMENT.equals(c.composeProject()) && "caddy".equals(c.composeService()))
                .toList();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private static int run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return p.exitValue();
    }
}
