package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.ContainerFileWrite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** writeFiles / readFile against a real alpine container. */
class ContainerFileWriteIntegrationTest {

    private static final String IMAGE = "alpine:3.20";

    private final UUID hostId = UUID.randomUUID();
    private DockerHostConnectionManager manager;
    private DockerEndpoint endpoint;
    private DockerClient docker;
    private ContainerFileServiceImpl service;
    private String id;

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
        id = docker.createContainerCmd(IMAGE).withName("shipir-it-write-" + UUID.randomUUID().toString().substring(0, 8))
                .withCmd("sh", "-c", "mkdir -p /data && sleep 300").exec().getId();
        docker.startContainerCmd(id).exec();
        Thread.sleep(800);
        service = new ContainerFileServiceImpl(manager, new ContainerDirectoryExecLister(manager));
    }

    @AfterEach
    void tearDown() {
        if (id != null) docker.removeContainerCmd(id).withForce(true).exec();
        if (manager != null) manager.evict(hostId);
    }

    private static ContainerFileWrite file(String path, String content) {
        return new ContainerFileWrite(path, content.getBytes(StandardCharsets.UTF_8), 0600);
    }

    @Test
    void writesFilesAndReadsThemBack() {
        service.writeFiles(hostId, endpoint, id, "/data",
                List.of(file("a.txt", "hello"), file("sub/deeper/b.txt", "nested")), 10_000);

        assertThat(new String(service.readFile(hostId, endpoint, id, "/data/a.txt", 1000), StandardCharsets.UTF_8))
                .isEqualTo("hello");
        assertThat(new String(service.readFile(hostId, endpoint, id, "/data/sub/deeper/b.txt", 1000), StandardCharsets.UTF_8))
                .isEqualTo("nested");
    }

    @Test
    void overwritesExistingFileAndKeepsMode() throws Exception {
        service.writeFiles(hostId, endpoint, id, "/data", List.of(file("a.txt", "one")), 1000);
        service.writeFiles(hostId, endpoint, id, "/data", List.of(file("a.txt", "two")), 1000);

        assertThat(new String(service.readFile(hostId, endpoint, id, "/data/a.txt", 1000), StandardCharsets.UTF_8))
                .isEqualTo("two");
        var tar = service.list(hostId, endpoint, id, "/data", 100_000);
        assertThat(tar).singleElement().satisfies(e -> assertThat(e.mode()).isEqualTo(0600));
    }

    @Test
    void writesIntoStoppedContainer() {
        docker.stopContainerCmd(id).withTimeout(1).exec();
        service.writeFiles(hostId, endpoint, id, "/data", List.of(file("x", "stopped")), 1000);
        assertThat(new String(service.readFile(hostId, endpoint, id, "/data/x", 1000), StandardCharsets.UTF_8))
                .isEqualTo("stopped");
    }

    @Test
    void readOfMissingFileIsNotFoundAndDirectoryIsInvalid() {
        assertThatThrownBy(() -> service.readFile(hostId, endpoint, id, "/data/nope", 1000))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.readFile(hostId, endpoint, id, "/data", 1000))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_PATH));
    }

    @Test
    void readOfOversizeFileIsSizeLimit() {
        service.writeFiles(hostId, endpoint, id, "/data", List.of(file("big", "x".repeat(2000))), 5000);
        assertThatThrownBy(() -> service.readFile(hostId, endpoint, id, "/data/big", 1000))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void writeIntoMissingDirectoryIsNotFound() {
        assertThatThrownBy(() -> service.writeFiles(hostId, endpoint, id, "/nope", List.of(file("a", "b")), 1000))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }

    @Test
    void urlEncodedDotsAreALiteralFileName() {
        service.writeFiles(hostId, endpoint, id, "/data", List.of(file("%2e%2e/x", "literal")), 1000);

        assertThat(new String(service.readFile(hostId, endpoint, id, "/data/%2e%2e/x", 1000), StandardCharsets.UTF_8))
                .isEqualTo("literal");
        assertThatThrownBy(() -> service.readFile(hostId, endpoint, id, "/x", 1000))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.NOT_FOUND));
    }
}
