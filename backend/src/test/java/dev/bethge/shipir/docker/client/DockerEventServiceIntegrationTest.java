package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.DockerEventDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Streams real events of the local Docker daemon. */
class DockerEventServiceIntegrationTest {

    private static final String IMAGE = "alpine:3.20";

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerEndpoint endpoint;
    private DockerClient docker;
    private DockerEventServiceImpl service;
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
        service = new DockerEventServiceImpl(manager);
    }

    @AfterEach
    void tearDown() {
        if (id != null) docker.removeContainerCmd(id).withForce(true).exec();
        if (manager != null) manager.evict(hostId);
    }

    @Test
    void containerLifecycleProducesEventsWithNameAndId() throws Exception {
        List<DockerEventDto> events = new CopyOnWriteArrayList<>();
        String name = "shipir-it-ev-" + UUID.randomUUID().toString().substring(0, 8);
        try (Closeable handle = service.stream(hostId, endpoint, events::add)) {
            id = docker.createContainerCmd(IMAGE).withName(name).withLabels(
                    java.util.Map.of("com.docker.compose.project", "shipir-it")).withCmd("sleep", "300").exec().getId();
            docker.startContainerCmd(id).exec();
            await().atMost(15, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(events).filteredOn(e -> id.equals(e.id())).extracting(DockerEventDto::action)
                            .contains("create", "start"));
        }
        assertThat(events).filteredOn(e -> id.equals(e.id())).allSatisfy(e -> {
            assertThat(e.type()).isEqualTo(DockerEventDto.Type.CONTAINER);
            assertThat(e.name()).isEqualTo(name);
            assertThat(e.composeProject()).isEqualTo("shipir-it");
        });
    }

    @Test
    void closeStopsDeliveryAndReportsNormalEndOnce() throws Exception {
        List<Throwable> ends = new CopyOnWriteArrayList<>();
        List<DockerEventDto> events = new CopyOnWriteArrayList<>();
        Closeable handle = service.stream(hostId, endpoint, events::add, ends::add);
        handle.close();
        handle.close();
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).until(() -> ends.size() == 1);
        assertThat(ends.get(0)).isNull();
        id = docker.createContainerCmd(IMAGE).withCmd("sleep", "300").exec().getId();
        Thread.sleep(500);
        assertThat(events).filteredOn(e -> id.equals(e.id())).isEmpty();
        assertThat(ends).hasSize(1);
    }
}
