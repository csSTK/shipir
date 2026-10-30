package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import org.testcontainers.DockerClientFactory;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the local daemon but touches only its own throwaway resources: it creates uniquely named volumes,
 * networks and containers, removes them again, and asserts only on those. Other containers, images, volumes and
 * networks on the daemon (other tests, other sessions, compose stacks) must not change the result.
 */
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

    /**
     * Asserts only on resources this test creates. Unrelated containers are created alongside them, so a test that
     * asserted over every container on the daemon would fail here.
     */
    @Test
    void candidatesAreStoppedContainersAndUnusedImagesOnly() throws Exception {
        String image = "alpine:3.20";
        var docker = DockerClientFactory.instance().client();
        String imageId = ensureImage(docker, image);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        // created but never started = not running = a prune candidate
        String stopped = null;
        String running = null;
        String foreignStopped = null;
        String foreignRunning = null;
        try {
            // unrelated containers, as other tests or sessions leave on a shared daemon; never asserted on
            foreignStopped = docker.createContainerCmd(image).withName("shipir-it-foreign-stopped-" + suffix)
                    .withLabels(Map.of("dev.bethge.shipir.test", "foreign")).withCmd("sleep", "300").exec().getId();
            foreignRunning = docker.createContainerCmd(image).withName("shipir-it-foreign-running-" + suffix)
                    .withLabels(Map.of("dev.bethge.shipir.test", "foreign")).withCmd("sleep", "300").exec().getId();
            docker.startContainerCmd(foreignRunning).exec();

            stopped = docker.createContainerCmd(image).withName("shipir-it-prune-stopped-" + suffix)
                    .withCmd("sleep", "300").exec().getId();
            running = docker.createContainerCmd(image).withName("shipir-it-prune-running-" + suffix)
                    .withCmd("sleep", "300").exec().getId();
            final String stoppedId = stopped;
            final String runningId = running;
            docker.startContainerCmd(runningId).exec();

            var containers = new ContainerServiceImpl(manager).listPrunable(hostId, endpoint);
            assertThat(containers).anyMatch(c -> stoppedId.equals(c.id()));
            assertThat(containers).noneMatch(c -> runningId.equals(c.id()));

            // both containers use the image, so it is in use and never a candidate, tagged or not
            var images = new ImageServiceImpl(manager);
            assertThat(images.listPrunable(hostId, endpoint, true)).noneMatch(c -> imageId.equals(c.id()));
            assertThat(images.listPrunable(hostId, endpoint, false)).noneMatch(c -> imageId.equals(c.id()));
            // isNotEmpty first: allSatisfy alone passes vacuously when the image is missing from the list
            assertThat(images.list(hostId, endpoint)).filteredOn(i -> imageId.equals(i.id())).isNotEmpty()
                    .allSatisfy(i -> assertThat(i.inUse()).isTrue());
        } finally {
            for (String id : new String[]{running, stopped, foreignRunning, foreignStopped}) {
                if (id == null) continue;
                try {
                    docker.removeContainerCmd(id).withForce(true).exec();
                } catch (Exception ignored) {
                    // cleanup must not mask the real failure
                }
            }
        }
    }

    /**
     * Returns the local image id, pulling the image once if needed. Skips the test (rather than failing it) when the
     * image is neither local nor pullable, e.g. offline.
     */
    private static String ensureImage(DockerClient docker, String image) {
        try {
            return docker.inspectImageCmd(image).exec().getId();
        } catch (NotFoundException notLocal) {
            // not local yet, pulled below
        }
        try {
            docker.pullImageCmd(image).start().awaitCompletion(2, TimeUnit.MINUTES);
            return docker.inspectImageCmd(image).exec().getId();
        } catch (Exception cannotPull) {
            Assumptions.assumeTrue(false, "Image " + image + " is not local and cannot be pulled: " + cannotPull.getMessage());
            return null;
        }
    }
}
