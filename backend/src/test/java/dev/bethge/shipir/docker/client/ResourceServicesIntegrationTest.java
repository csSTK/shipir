package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.NetworkDto;
import dev.bethge.shipir.docker.dto.VolumeDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Network;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against the local Docker daemon (the same one Testcontainers uses). */
class ResourceServicesIntegrationTest {

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerEndpoint endpoint;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(10)));
        endpoint = manager.localEndpoint();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.evict(hostId);
    }

    @Test
    void listsCreatedNetworkAndVolume() {
        String volumeName = "shipir-it-" + UUID.randomUUID().toString().substring(0, 8);
        var docker = DockerClientFactory.instance().client();
        docker.createVolumeCmd().withName(volumeName).exec();
        try (Network network = Network.newNetwork()) {
            String networkId = network.getId(); // created lazily on first access
            NetworkDto dto = new NetworkServiceImpl(manager).list(hostId, endpoint).stream()
                    .filter(n -> networkId.equals(n.id())).findFirst().orElseThrow();
            assertThat(dto.driver()).isEqualTo("bridge");
            assertThat(dto.containerCount()).isZero();
            assertThat(dto.internal()).isFalse();

            VolumeDto volume = new VolumeServiceImpl(manager).list(hostId, endpoint).stream()
                    .filter(v -> volumeName.equals(v.name())).findFirst().orElseThrow();
            assertThat(volume.driver()).isEqualTo("local");
            assertThat(volume.inUse()).isFalse();
        } finally {
            docker.removeVolumeCmd(volumeName).exec();
        }
    }

    @Test
    void listsImagesWithUseFlag() {
        assertThat(new ImageServiceImpl(manager).list(hostId, endpoint)).allSatisfy(i -> {
            assertThat(i.id()).isNotBlank();
            assertThat(i.repoTags()).isNotNull();
        });
    }
}
