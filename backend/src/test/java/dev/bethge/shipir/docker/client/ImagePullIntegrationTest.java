package dev.bethge.shipir.docker.client;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real pull against the local daemon (needs network for the registry); skipped without Docker. */
class ImagePullIntegrationTest {

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private ImageServiceImpl service;
    private DockerEndpoint endpoint;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(20)));
        endpoint = manager.localEndpoint();
        service = new ImageServiceImpl(manager);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.evict(hostId);
    }

    @Test
    void pullsBusyboxAndReportsProgress() {
        List<String> lines = new ArrayList<>();
        service.pull(hostId, endpoint, "busybox:1.36", null, lines::add);
        assertThat(lines).isNotEmpty();
        assertThat(service.list(hostId, endpoint)).anyMatch(i -> i.repoTags().contains("busybox:1.36"));
    }

    @Test
    void unknownTagIsNotFoundOrPullFailed() {
        assertThatThrownBy(() -> service.pull(hostId, endpoint, "busybox:no-such-tag-" + UUID.randomUUID(), null, l -> { }))
                .isInstanceOfSatisfying(DockerConnectionException.class, e ->
                        assertThat(e.getCode()).isIn(DockerErrorCode.NOT_FOUND, DockerErrorCode.PULL_FAILED));
    }
}
