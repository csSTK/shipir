package dev.bethge.shipir.proxy;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real Docker daemon: a published container that is removed and recreated under the same name is attached to the
 * {@code shipir-proxy} network again by the reconciler. Uses the product's fixed network name, so it is skipped when
 * that network exists (a real proxy is set up on this daemon) and removes only what it created.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(PostgresTestConfiguration.class)
class ProxyRouteReconcilerIntegrationTest {

    private static final String IMAGE = "alpine:3.20";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("shipir.docker.local-socket", () -> "unix://"
                + DockerClientFactory.instance().getRemoteDockerUnixSocketPath());
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ProxyInstanceRepository instances;
    @Autowired
    ProxyRouteRepository routes;
    @Autowired
    ProxyRouteReconciler reconciler;

    @Test
    void aPublishedContainerRecreatedUnderTheSameNameIsAttachedAgain() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon available");
        DockerClient docker = DockerClientFactory.lazyClient();
        assumeTrue(docker.listNetworksCmd().withNameFilter("shipir-proxy").exec().stream()
                .noneMatch(n -> "shipir-proxy".equals(n.getName())), "network shipir-proxy already exists");
        assumeTrue(ensureImage(docker), IMAGE + " not available");

        String name = "recon-it-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Recon', 'LOCAL', id FROM users WHERE username = 'admin'");
        UUID hostId = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        instances.save(ProxyInstance.create(hostId, ProxyType.CADDY, "ops@example.com", CertMode.INTERNAL));
        routes.save(ProxyRoute.create(hostId, name + ".example.com", List.of(), name, 80, BackendScheme.HTTP, null, null,
                CertMode.INTERNAL, null, true, Map.of(), Map.of()));
        String networkId = docker.createNetworkCmd().withName("shipir-proxy").exec().getId();
        try {
            String first = startContainer(docker, name);
            reconciler.syncWorkers();
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                    .untilAsserted(() -> assertThat(onProxyNetwork(docker, first)).as("first container").isTrue());

            docker.removeContainerCmd(first).withForce(true).exec();
            String second = startContainer(docker, name);
            assertThat(second).isNotEqualTo(first);
            // compose would create it without the manual network connection; the start event must repair that
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                    .untilAsserted(() -> assertThat(onProxyNetwork(docker, second)).as("recreated container").isTrue());

            // a container that is not routed stays off the proxy network
            String other = startContainer(docker, name + "-other");
            TimeUnit.SECONDS.sleep(2);
            assertThat(onProxyNetwork(docker, other)).isFalse();
            docker.removeContainerCmd(other).withForce(true).exec();
        } finally {
            // each step is guarded so a cleanup problem never replaces the test's own failure
            quietly(() -> jdbc.update("DELETE FROM docker_hosts")); // cascades the proxy rows
            quietly(reconciler::syncWorkers); // stops the worker
            for (String n : List.of(name, name + "-other")) {
                quietly(() -> docker.removeContainerCmd(n).withForce(true).exec());
            }
            quietly(() -> docker.removeNetworkCmd(networkId).exec());
        }
    }

    private static void quietly(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            // best effort
        }
    }

    private static String startContainer(DockerClient docker, String name) {
        String id = docker.createContainerCmd(IMAGE).withName(name).withCmd("sleep", "300").exec().getId();
        docker.startContainerCmd(id).exec();
        return id;
    }

    private static boolean onProxyNetwork(DockerClient docker, String id) {
        InspectContainerResponse info = docker.inspectContainerCmd(id).exec();
        return info.getNetworkSettings().getNetworks().containsKey("shipir-proxy");
    }

    private static boolean ensureImage(DockerClient docker) {
        try {
            docker.inspectImageCmd(IMAGE).exec();
            return true;
        } catch (RuntimeException e) {
            try {
                return docker.pullImageCmd(IMAGE).start().awaitCompletion(60, TimeUnit.SECONDS);
            } catch (Exception pull) {
                return false;
            }
        }
    }
}
