package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Remove operations against the local Docker daemon (same one Testcontainers uses). Uses the already present alpine image. */
class RemoveOperationsIntegrationTest {

    private static final String IMAGE = "alpine:3.20";

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerEndpoint endpoint;
    private DockerClient docker;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(20)));
        endpoint = manager.localEndpoint();
        docker = DockerClientFactory.instance().client();
        try {
            docker.inspectImageCmd(IMAGE).exec();
        } catch (Exception notPresent) {
            docker.pullImageCmd(IMAGE).start().awaitCompletion();
        }
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.evict(hostId);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String createContainer(String name, boolean start) {
        String id = docker.createContainerCmd(IMAGE).withName(name).withCmd("sleep", "300").exec().getId();
        if (start) docker.startContainerCmd(id).exec();
        return id;
    }

    private boolean containerExists(String id) {
        return docker.listContainersCmd().withShowAll(true).exec().stream().anyMatch(c -> id.equals(c.getId()));
    }

    @Test
    void removesStoppedContainerAndReportsNotFoundAfterwards() {
        String id = createContainer(unique("shipir-it-stopped"), false);
        var service = new ContainerServiceImpl(manager);

        service.removeContainer(hostId, endpoint, id, false);

        assertThat(containerExists(id)).isFalse();
        assertThatThrownBy(() -> service.removeContainer(hostId, endpoint, id, false))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }

    @Test
    void runningContainerNeedsForce() {
        String id = createContainer(unique("shipir-it-running"), true);
        var service = new ContainerServiceImpl(manager);
        try {
            assertThatThrownBy(() -> service.removeContainer(hostId, endpoint, id, false))
                    .isInstanceOfSatisfying(DockerConnectionException.class,
                            e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.IN_USE));
            assertThat(containerExists(id)).isTrue();

            service.removeContainer(hostId, endpoint, id, true);

            assertThat(containerExists(id)).isFalse();
        } finally {
            if (containerExists(id)) docker.removeContainerCmd(id).withForce(true).exec();
        }
    }

    @Test
    void removesUnusedVolumeButRefusesVolumeInUse() {
        String free = unique("shipir-it-vol");
        String used = unique("shipir-it-vol-used");
        docker.createVolumeCmd().withName(free).exec();
        docker.createVolumeCmd().withName(used).exec();
        String container = docker.createContainerCmd(IMAGE).withName(unique("shipir-it-holder"))
                .withCmd("true").withHostConfig(new com.github.dockerjava.api.model.HostConfig()
                        .withBinds(com.github.dockerjava.api.model.Bind.parse(used + ":/data"))).exec().getId();
        var service = new VolumeServiceImpl(manager);
        try {
            service.removeVolume(hostId, endpoint, free);
            assertThat(new VolumeServiceImpl(manager).list(hostId, endpoint)).noneMatch(v -> free.equals(v.name()));

            assertThatThrownBy(() -> service.removeVolume(hostId, endpoint, used))
                    .isInstanceOfSatisfying(DockerConnectionException.class,
                            e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.IN_USE));
        } finally {
            docker.removeContainerCmd(container).withForce(true).exec();
            docker.removeVolumeCmd(used).exec();
        }
    }

    @Test
    void removesUnusedNetworkRefusesBuiltin() {
        String name = unique("shipir-it-net");
        String id = docker.createNetworkCmd().withName(name).exec().getId();
        var service = new NetworkServiceImpl(manager);

        service.removeNetwork(hostId, endpoint, id);

        assertThat(service.list(hostId, endpoint)).noneMatch(n -> id.equals(n.id()));
        String bridge = service.list(hostId, endpoint).stream().filter(n -> "bridge".equals(n.name()))
                .findFirst().orElseThrow().id();
        assertThatThrownBy(() -> service.removeNetwork(hostId, endpoint, bridge))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.BUILTIN_NETWORK));
    }

    @Test
    void networkWithContainerIsRefused() {
        String name = unique("shipir-it-net-used");
        String netId = docker.createNetworkCmd().withName(name).exec().getId();
        String container = docker.createContainerCmd(IMAGE).withName(unique("shipir-it-attached"))
                .withCmd("sleep", "300").withHostConfig(new com.github.dockerjava.api.model.HostConfig().withNetworkMode(name))
                .exec().getId();
        docker.startContainerCmd(container).exec();
        try {
            assertThatThrownBy(() -> new NetworkServiceImpl(manager).removeNetwork(hostId, endpoint, netId))
                    .isInstanceOfSatisfying(DockerConnectionException.class,
                            e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.IN_USE));
        } finally {
            docker.removeContainerCmd(container).withForce(true).exec();
            docker.removeNetworkCmd(netId).exec();
        }
    }

    @Test
    void removesUnusedImageAndRefusesImageInUse() {
        // A committed container yields an image id nothing else references.
        String holder = createContainer(unique("shipir-it-commit"), false);
        String uniqueImage = docker.commitCmd(holder).exec();
        docker.removeContainerCmd(holder).withForce(true).exec();
        String user = docker.createContainerCmd(IMAGE).withName(unique("shipir-it-imguser")).withCmd("true").exec().getId();
        String alpineId = docker.inspectImageCmd(IMAGE).exec().getId();
        var service = new ImageServiceImpl(manager);
        try {
            service.removeImage(hostId, endpoint, uniqueImage);
            assertThat(service.list(hostId, endpoint)).noneMatch(i -> uniqueImage.equals(i.id()));

            assertThatThrownBy(() -> service.removeImage(hostId, endpoint, alpineId))
                    .isInstanceOfSatisfying(DockerConnectionException.class,
                            e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.IN_USE));
        } finally {
            docker.removeContainerCmd(user).withForce(true).exec();
        }
    }
}
