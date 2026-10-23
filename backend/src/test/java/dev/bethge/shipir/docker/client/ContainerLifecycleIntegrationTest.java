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

/** Lifecycle actions against the local Docker daemon; read timeout 2 s so the stop grace period exceeds it. */
class ContainerLifecycleIntegrationTest {

    private static final String IMAGE = "alpine:3.20";

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerEndpoint endpoint;
    private DockerClient docker;
    private ContainerServiceImpl service;
    private String id;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(2)));
        endpoint = manager.localEndpoint();
        docker = DockerClientFactory.instance().client();
        try {
            docker.inspectImageCmd(IMAGE).exec();
        } catch (Exception notPresent) {
            docker.pullImageCmd(IMAGE).start().awaitCompletion();
        }
        service = new ContainerServiceImpl(manager);
        id = docker.createContainerCmd(IMAGE).withName("shipir-it-life-" + UUID.randomUUID().toString().substring(0, 8))
                .withCmd("sleep", "300").exec().getId();
    }

    @AfterEach
    void tearDown() {
        if (id != null) docker.removeContainerCmd(id).withForce(true).exec();
        if (manager != null) manager.evict(hostId);
    }

    private String state() {
        var s = docker.inspectContainerCmd(id).exec().getState();
        return Boolean.TRUE.equals(s.getPaused()) ? "paused" : s.getStatus();
    }

    private static void assertCode(Throwable t, DockerErrorCode code) {
        assertThat(t).isInstanceOfSatisfying(DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void startPauseUnpauseStopRestartKillChangeRealState() {
        service.startContainer(hostId, endpoint, id);
        assertThat(state()).isEqualTo("running");

        service.pauseContainer(hostId, endpoint, id);
        assertThat(state()).isEqualTo("paused");

        service.unpauseContainer(hostId, endpoint, id);
        assertThat(state()).isEqualTo("running");

        service.restartContainer(hostId, endpoint, id, 1);
        assertThat(state()).isEqualTo("running");

        service.killContainer(hostId, endpoint, id);
        assertThat(state()).isEqualTo("exited");

        service.startContainer(hostId, endpoint, id);
        service.stopContainer(hostId, endpoint, id, 1);
        assertThat(state()).isEqualTo("exited");
    }

    @Test
    void stopWithTimeoutLongerThanReadTimeoutDoesNotTimeOut() {
        // sleep as PID 1 ignores SIGTERM, so stop waits the whole grace period (4 s > 2 s read timeout)
        service.startContainer(hostId, endpoint, id);

        service.stopContainer(hostId, endpoint, id, 4);

        assertThat(state()).isEqualTo("exited");
    }

    @Test
    void wrongStateIsInvalidState() {
        assertThatThrownBy(() -> service.pauseContainer(hostId, endpoint, id)).satisfies(e -> assertCode(e, DockerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> service.killContainer(hostId, endpoint, id)).satisfies(e -> assertCode(e, DockerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> service.stopContainer(hostId, endpoint, id, 1)).satisfies(e -> assertCode(e, DockerErrorCode.INVALID_STATE));

        service.startContainer(hostId, endpoint, id);
        assertThatThrownBy(() -> service.startContainer(hostId, endpoint, id)).satisfies(e -> assertCode(e, DockerErrorCode.INVALID_STATE));
        assertThatThrownBy(() -> service.unpauseContainer(hostId, endpoint, id)).satisfies(e -> assertCode(e, DockerErrorCode.INVALID_STATE));
        service.pauseContainer(hostId, endpoint, id);
        assertThatThrownBy(() -> service.pauseContainer(hostId, endpoint, id)).satisfies(e -> assertCode(e, DockerErrorCode.INVALID_STATE));
    }

    @Test
    void unknownIdIsNotFound() {
        String missing = "shipir-does-not-exist";
        assertThatThrownBy(() -> service.startContainer(hostId, endpoint, missing)).satisfies(e -> assertCode(e, DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.stopContainer(hostId, endpoint, missing, 1)).satisfies(e -> assertCode(e, DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.restartContainer(hostId, endpoint, missing, 1)).satisfies(e -> assertCode(e, DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.pauseContainer(hostId, endpoint, missing)).satisfies(e -> assertCode(e, DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.unpauseContainer(hostId, endpoint, missing)).satisfies(e -> assertCode(e, DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.killContainer(hostId, endpoint, missing)).satisfies(e -> assertCode(e, DockerErrorCode.NOT_FOUND));
    }
}
