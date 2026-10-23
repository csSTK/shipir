package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.StackSummaryDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against the local Docker daemon (the same one Testcontainers uses). */
class ContainerServiceIntegrationTest {

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private ContainerService service;
    private DockerEndpoint endpoint;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon available, skipping Docker integration test");
        String socket = "unix://" + DockerClientFactory.instance().getRemoteDockerUnixSocketPath();
        manager = new DockerHostConnectionManager(new DockerProperties(socket, Duration.ofSeconds(3), Duration.ofSeconds(10)));
        service = new ContainerServiceImpl(manager);
        endpoint = manager.localEndpoint();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.evict(hostId);
    }

    @Test
    void labeledContainerAppearsInContainersAndItsStack() {
        String project = "shipir-it-" + UUID.randomUUID().toString().substring(0, 8);
        try (GenericContainer<?> container = new GenericContainer<>("alpine:3")
                .withCommand("sleep", "120")
                .withLabel("com.docker.compose.project", project)
                .withLabel("com.docker.compose.service", "sleeper")
                .withLabel("com.docker.compose.project.working_dir", "/srv/" + project)) {
            container.start();

            ContainerDto dto = service.listContainers(hostId, endpoint).stream()
                    .filter(c -> container.getContainerId().equals(c.id()))
                    .findFirst().orElseThrow();
            assertThat(dto.state()).isEqualTo("running");
            assertThat(dto.composeProject()).isEqualTo(project);
            assertThat(dto.composeService()).isEqualTo("sleeper");
            assertThat(dto.image()).isEqualTo("alpine:3");

            assertThat(service.listStacks(hostId, endpoint)).contains(
                    new StackSummaryDto(project, 1, 1, StackSummaryDto.Status.RUNNING, "/srv/" + project));
        }
    }
}
