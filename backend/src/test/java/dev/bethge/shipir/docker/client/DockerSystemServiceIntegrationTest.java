package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.HealthCheck;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import dev.bethge.shipir.docker.dto.DockerSystemInfoDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Runs against the local Docker daemon (the same one Testcontainers uses). */
class DockerSystemServiceIntegrationTest {

    private DockerSystemService service;
    private DockerEndpoint endpoint;
    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(10)));
        service = new DockerSystemServiceImpl(manager);
        endpoint = manager.localEndpoint();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.evict(hostId);
    }

    @Test
    void pingLocalReturnsOkAndVersion() {
        ConnectionTestResult result = service.ping(endpoint);

        assertThat(result.ok()).isTrue();
        assertThat(result.dockerVersion()).isNotBlank();
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void infoReturnsPlausibleCounts() {
        DockerSystemInfoDto info = service.info(hostId, endpoint);

        assertThat(info.dockerVersion()).isNotBlank();
        assertThat(info.cpus()).isPositive();
        assertThat(info.memoryBytes()).isPositive();
        assertThat(info.containersRunning()).isNotNegative();
        assertThat(info.containersStopped()).isNotNegative();
        assertThat(info.images()).isNotNegative();
        assertThat(info.volumes()).isNotNegative();
    }

    /**
     * Asserts only on the test's own container. The daemon is shared with parallel test runs, so host-wide
     * counts can rise and fall at any time; only lower bounds implied by our own container are stable.
     */
    @Test
    void infoCountsRunningAndHealthyContainer() {
        try (GenericContainer<?> container = healthyContainer()) {
            container.start();

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(healthStatusOf(container)).isEqualTo("healthy");
                DockerSystemInfoDto info = service.info(hostId, endpoint);
                assertThat(info.containersRunning()).isGreaterThanOrEqualTo(1);
                assertThat(info.containersHealthy()).isGreaterThanOrEqualTo(1);
            });
        }
    }

    private static GenericContainer<?> healthyContainer() {
        return new GenericContainer<>("alpine:3")
                .withCommand("sleep", "120")
                .withCreateContainerCmdModifier(cmd -> cmd.withHealthcheck(new HealthCheck()
                        .withTest(List.of("CMD-SHELL", "true"))
                        .withInterval(TimeUnit.SECONDS.toNanos(1))
                        .withTimeout(TimeUnit.SECONDS.toNanos(1))
                        .withRetries(1)));
    }

    private String healthStatusOf(GenericContainer<?> container) {
        try (DockerClient client = manager.createClient(endpoint)) {
            var state = client.inspectContainerCmd(container.getContainerId()).exec().getState();
            return state.getHealth() == null ? null : state.getHealth().getStatus();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
