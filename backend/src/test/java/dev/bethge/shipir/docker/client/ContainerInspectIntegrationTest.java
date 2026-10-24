package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Inspect against the local Docker daemon (skipped without Docker). */
class ContainerInspectIntegrationTest {

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
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(5)));
        endpoint = manager.localEndpoint();
        docker = DockerClientFactory.instance().client();
        try {
            docker.inspectImageCmd(IMAGE).exec();
        } catch (Exception notPresent) {
            docker.pullImageCmd(IMAGE).start().awaitCompletion();
        }
        service = new ContainerServiceImpl(manager);
        id = docker.createContainerCmd(IMAGE).withName("shipir-it-inspect-" + UUID.randomUUID().toString().substring(0, 8))
                .withEnv("FOO=bar").withLabels(java.util.Map.of("shipir.test", "1"))
                .withHostConfig(com.github.dockerjava.api.model.HostConfig.newHostConfig()
                        .withRestartPolicy(com.github.dockerjava.api.model.RestartPolicy.onFailureRestart(3)))
                .withCmd("sleep", "300").exec().getId();
        docker.startContainerCmd(id).exec();
    }

    @AfterEach
    void tearDown() {
        if (id != null) docker.removeContainerCmd(id).withForce(true).exec();
        if (manager != null) manager.evict(hostId);
    }

    @Test
    void inspectReturnsRealContainerDetails() {
        ContainerDetailsDto d = service.inspect(hostId, endpoint, id);

        assertThat(d.id()).isEqualTo(id);
        assertThat(d.state()).isEqualTo("running");
        assertThat(d.image()).isEqualTo(IMAGE);
        assertThat(d.env()).contains(new ContainerDetailsDto.EnvVar("FOO", "bar"));
        assertThat(d.labels()).containsEntry("shipir.test", "1");
        assertThat(d.restartPolicy()).isEqualTo(new ContainerDetailsDto.RestartPolicy("on-failure", 3));
        assertThat(d.command()).containsExactly("sleep", "300");
        assertThat(d.startedAt()).isNotNull();
        assertThat(d.networks()).isNotEmpty();
        assertThat(d.health()).isNull();
    }

    @Test
    void unknownContainerIsNotFound() {
        assertThatThrownBy(() -> service.inspect(hostId, endpoint, "doesnotexist1")).isInstanceOfSatisfying(
                DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }
}
