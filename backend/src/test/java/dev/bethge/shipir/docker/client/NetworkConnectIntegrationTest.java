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

/** Idempotent connect/disconnect against a real daemon. */
class NetworkConnectIntegrationTest {

    private static final String IMAGE = "alpine:3.20";

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerEndpoint endpoint;
    private DockerClient docker;
    private NetworkServiceImpl service;
    private String containerId;
    private String networkId;
    private String networkName;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(15)));
        endpoint = manager.localEndpoint();
        docker = DockerClientFactory.instance().client();
        try {
            docker.inspectImageCmd(IMAGE).exec();
        } catch (Exception notPresent) {
            docker.pullImageCmd(IMAGE).start().awaitCompletion();
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        networkName = "shipir-it-net-" + suffix;
        networkId = docker.createNetworkCmd().withName(networkName).exec().getId();
        containerId = docker.createContainerCmd(IMAGE).withName("shipir-it-conn-" + suffix)
                .withCmd("sleep", "300").exec().getId();
        docker.startContainerCmd(containerId).exec();
        service = new NetworkServiceImpl(manager);
    }

    @AfterEach
    void tearDown() {
        if (containerId != null) docker.removeContainerCmd(containerId).withForce(true).exec();
        if (networkId != null) docker.removeNetworkCmd(networkId).exec();
        if (manager != null) manager.evict(hostId);
    }

    private boolean connected() {
        return docker.inspectContainerCmd(containerId).exec().getNetworkSettings().getNetworks()
                .containsKey(networkName);
    }

    @Test
    void connectTwiceIsNoOp() {
        service.connectContainer(hostId, endpoint, networkId, containerId);
        assertThat(connected()).isTrue();

        service.connectContainer(hostId, endpoint, networkId, containerId);
        assertThat(connected()).isTrue();
    }

    @Test
    void connectByNetworkNameWorks() {
        service.connectContainer(hostId, endpoint, networkName, containerId);
        assertThat(connected()).isTrue();
    }

    @Test
    void disconnectRemovesAndSecondDisconnectIsNoOp() {
        service.connectContainer(hostId, endpoint, networkId, containerId);

        service.disconnectContainer(hostId, endpoint, networkId, containerId);
        assertThat(connected()).isFalse();

        service.disconnectContainer(hostId, endpoint, networkId, containerId);
        assertThat(connected()).isFalse();
    }

    @Test
    void unknownNetworkOrContainerIsNotFound() {
        assertThatThrownBy(() -> service.connectContainer(hostId, endpoint, "nope-" + networkId, containerId))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.connectContainer(hostId, endpoint, networkId, "nope-" + containerId))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }

    @Test
    void stoppedContainerConnectTwiceIsNoOpAndDisconnectTwiceDetaches() {
        docker.stopContainerCmd(containerId).withTimeout(1).exec();

        service.connectContainer(hostId, endpoint, networkId, containerId);
        assertThat(connected()).isTrue();
        service.connectContainer(hostId, endpoint, networkId, containerId);
        assertThat(connected()).isTrue();

        service.disconnectContainer(hostId, endpoint, networkId, containerId);
        assertThat(connected()).isFalse();
        service.disconnectContainer(hostId, endpoint, networkId, containerId);
        assertThat(connected()).isFalse();
    }
}
