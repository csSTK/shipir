package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.ContainerStatsDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Samples a real container on the local Docker daemon. */
class ContainerStatsServiceIntegrationTest {

    private static final String IMAGE = "alpine:3.20";

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerEndpoint endpoint;
    private DockerClient docker;
    private ContainerStatsServiceImpl service;
    private String id;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(10)));
        endpoint = manager.localEndpoint();
        docker = DockerClientFactory.instance().client();
        try {
            docker.inspectImageCmd(IMAGE).exec();
        } catch (Exception notPresent) {
            docker.pullImageCmd(IMAGE).start().awaitCompletion();
        }
        service = new ContainerStatsServiceImpl(manager);
        id = docker.createContainerCmd(IMAGE).withName("shipir-it-stats-" + UUID.randomUUID().toString().substring(0, 8))
                .withCmd("sh", "-c", "sleep 300").exec().getId();
        docker.startContainerCmd(id).exec();
    }

    @AfterEach
    void tearDown() {
        if (id != null) docker.removeContainerCmd(id).withForce(true).exec();
        if (manager != null) manager.evict(hostId);
    }

    @Test
    void snapshotReturnsPlausibleValues() {
        ContainerStatsDto dto = service.snapshot(hostId, endpoint, id);

        assertThat(dto.time()).isNotNull();
        assertThat(dto.memoryUsageBytes()).isPositive();
        assertThat(dto.memoryLimitBytes()).isPositive();
        assertThat(dto.cpuPercent()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void streamDeliversSamplesAndCloseStopsIt() throws Exception {
        List<ContainerStatsDto> samples = new CopyOnWriteArrayList<>();
        CountDownLatch two = new CountDownLatch(2);
        CountDownLatch ended = new CountDownLatch(1);

        Closeable handle = service.stream(hostId, endpoint, id, s -> { samples.add(s); two.countDown(); }, e -> ended.countDown());
        assertThat(two.await(15, TimeUnit.SECONDS)).isTrue();
        handle.close();
        handle.close();
        assertThat(ended.await(10, TimeUnit.SECONDS)).isTrue();
        int afterClose = samples.size();
        Thread.sleep(2500);

        assertThat(samples).as("no samples after close").hasSize(afterClose);
    }

    @Test
    void unknownContainerIsNotFound() {
        assertThatThrownBy(() -> service.snapshot(hostId, endpoint, "does-not-exist-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.stream(hostId, endpoint, "does-not-exist-" + UUID.randomUUID(), s -> { }))
                .isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }
}
