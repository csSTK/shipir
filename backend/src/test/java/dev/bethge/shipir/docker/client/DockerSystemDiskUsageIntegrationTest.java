package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.DiskUsageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real {@code /system/df} of the local daemon (the one Testcontainers uses); skipped without Docker. The read timeout is
 * generous because the daemon computes all sizes on each call (about 3 s with many images on a dev machine).
 */
class DockerSystemDiskUsageIntegrationTest {

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerSystemService service;
    private DockerEndpoint endpoint;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(10), Duration.ofSeconds(30)));
        service = new DockerSystemServiceImpl(manager);
        endpoint = manager.localEndpoint();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.evict(hostId);
    }

    @Test
    void reportsImagesContainersVolumesAndBuildCacheOfTheDaemon() {
        DiskUsageDto usage = service.diskUsage(hostId, endpoint);

        assertThat(usage.images().count()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(usage.images().totalSize()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(usage.containers().count()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(usage.volumes().count()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(usage.buildCache().count()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(usage.images().reclaimableSize()).isLessThanOrEqualTo(usage.images().totalSize());
    }
}
