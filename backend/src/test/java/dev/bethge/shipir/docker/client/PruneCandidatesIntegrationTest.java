package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Read-only against the local daemon: lists candidates for labelled throwaway resources, removes nothing it did not create. */
class PruneCandidatesIntegrationTest {

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerEndpoint endpoint;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon available");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(10)));
        endpoint = manager.localEndpoint();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.evict(hostId);
    }

    @Test
    void unusedVolumeAndNetworkAreCandidatesAndBuiltinNetworksNever() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String volume = "shipir-prune-vol-" + suffix;
        String netName = "shipir-prune-net-" + suffix;
        var docker = DockerClientFactory.instance().client();
        docker.createVolumeCmd().withName(volume)
                .withLabels(Map.of("com.docker.compose.project", "proj-" + suffix)).exec();
        String networkId = docker.createNetworkCmd().withName(netName).exec().getId();
        try {
            PruneCandidateDto v = new VolumeServiceImpl(manager).listPrunable(hostId, endpoint).stream()
                    .filter(c -> volume.equals(c.id())).findFirst().orElseThrow();
            assertThat(v.composeProject()).isEqualTo("proj-" + suffix);

            var networks = new NetworkServiceImpl(manager).listPrunable(hostId, endpoint);
            assertThat(networks).anyMatch(n -> networkId.equals(n.id()) && netName.equals(n.name()));
            assertThat(networks).noneMatch(n -> java.util.Set.of("bridge", "host", "none").contains(n.name()));
        } finally {
            docker.removeVolumeCmd(volume).exec();
            docker.removeNetworkCmd(networkId).exec();
        }
    }

    @Test
    void candidatesAreStoppedContainersAndUnusedImagesOnly() {
        var containers = new ContainerServiceImpl(manager).listPrunable(hostId, endpoint);
        var all = new ContainerServiceImpl(manager).listContainers(hostId, endpoint);
        assertThat(containers).allSatisfy(c -> assertThat(all)
                .anyMatch(a -> a.id().equals(c.id()) && !java.util.Set.of("running", "paused", "restarting").contains(a.state())));
        var images = new ImageServiceImpl(manager);
        var inUse = images.list(hostId, endpoint).stream().filter(i -> i.inUse()).map(i -> i.id()).toList();
        assertThat(images.listPrunable(hostId, endpoint, true)).noneMatch(c -> inUse.contains(c.id()));
        assertThat(images.listPrunable(hostId, endpoint, false).size())
                .isLessThanOrEqualTo(images.listPrunable(hostId, endpoint, true).size());
    }
}
