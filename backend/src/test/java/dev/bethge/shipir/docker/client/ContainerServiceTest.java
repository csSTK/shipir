package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.StackSummaryDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerServiceTest {

    @TempDir
    Path dir;

    private final List<SocketChannel> accepted = new ArrayList<>();
    private ServerSocketChannel server;

    @AfterEach
    void tearDown() throws IOException {
        for (SocketChannel c : accepted) c.close();
        if (server != null) server.close();
    }

    private static Container container(String name, String state, String status, Map<String, String> labels) {
        Container c = mock(Container.class);
        when(c.getId()).thenReturn("id-" + name);
        when(c.getNames()).thenReturn(new String[]{"/" + name});
        when(c.getImage()).thenReturn("nginx:1");
        when(c.getState()).thenReturn(state);
        when(c.getStatus()).thenReturn(status);
        when(c.getCreated()).thenReturn(1_700_000_000L);
        when(c.getLabels()).thenReturn(labels);
        return c;
    }

    private static ContainerService serviceReturning(List<Container> containers) {
        DockerHostConnectionManager manager = mock(DockerHostConnectionManager.class);
        DockerClient client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
        when(manager.readTimeout()).thenReturn(Duration.ofSeconds(5));
        when(manager.client(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(client);
        when(client.listContainersCmd().withShowAll(true).exec()).thenReturn(containers);
        return new ContainerServiceImpl(manager);
    }

    private static final DockerEndpoint ENDPOINT = new DockerEndpoint.Local("unix:///x.sock");

    @Test
    void mapsContainerFieldsToOwnDto() {
        Container c = container("web", "running", "Up 5 seconds (healthy)",
                Map.of("com.docker.compose.project", "shop", "com.docker.compose.service", "frontend"));
        ContainerPort port = mock(ContainerPort.class);
        when(port.getPublicPort()).thenReturn(8080);
        when(port.getPrivatePort()).thenReturn(80);
        when(port.getType()).thenReturn("tcp");
        when(c.getPorts()).thenReturn(new ContainerPort[]{port});

        ContainerDto dto = serviceReturning(List.of(c)).listContainers(UUID.randomUUID(), ENDPOINT).getFirst();

        assertThat(dto).isEqualTo(new ContainerDto("id-web", "web", "nginx:1", "running", "Up 5 seconds (healthy)",
                "healthy", Instant.ofEpochSecond(1_700_000_000L), List.of("8080:80/tcp"), "shop", "frontend"));
    }

    @Test
    void containerWithoutLabelsPortsOrHealthcheckHasNullsAndEmptyPorts() {
        Container c = container("solo", "exited", "Exited (0) 2 hours ago", null);

        ContainerDto dto = serviceReturning(List.of(c)).listContainers(UUID.randomUUID(), ENDPOINT).getFirst();

        assertThat(dto.health()).isNull();
        assertThat(dto.ports()).isEmpty();
        assertThat(dto.composeProject()).isNull();
        assertThat(dto.composeService()).isNull();
    }

    @Test
    void containersWithoutComposeLabelAreNotPartOfAnyStack() {
        Container solo = container("solo", "running", "Up", Map.of());
        Container nullLabels = container("other", "running", "Up", null);

        assertThat(serviceReturning(List.of(solo, nullLabels)).listStacks(UUID.randomUUID(), ENDPOINT)).isEmpty();
    }

    @Test
    void derivesStackStatusAndCounts() {
        Map<String, String> shop = Map.of("com.docker.compose.project", "shop",
                "com.docker.compose.project.working_dir", "/srv/shop");
        Map<String, String> blog = Map.of("com.docker.compose.project", "blog");
        Map<String, String> off = Map.of("com.docker.compose.project", "archive");
        List<Container> all = List.of(
                container("shop-a", "running", "Up", shop), container("shop-b", "running", "Up", shop),
                container("blog-a", "running", "Up", blog), container("blog-b", "exited", "Exited", blog),
                container("arch-a", "exited", "Exited", off),
                container("solo", "running", "Up", Map.of()));

        List<StackSummaryDto> stacks = serviceReturning(all).listStacks(UUID.randomUUID(), ENDPOINT);

        assertThat(stacks).containsExactly(
                new StackSummaryDto("archive", 1, 0, StackSummaryDto.Status.STOPPED, null),
                new StackSummaryDto("blog", 2, 1, StackSummaryDto.Status.PARTIAL, null),
                new StackSummaryDto("shop", 2, 2, StackSummaryDto.Status.RUNNING, "/srv/shop"));
    }

    private ContainerService realService(Duration read) {
        return new ContainerServiceImpl(
                new DockerHostConnectionManager(new DockerProperties("unix:///unused.sock", Duration.ofSeconds(1), read)));
    }

    @Test
    void missingSocketThrowsUnreachable() {
        ContainerService service = realService(Duration.ofSeconds(1));
        var endpoint = new DockerEndpoint.Local("unix://" + dir.resolve("missing.sock"));

        assertThatThrownBy(() -> service.listContainers(UUID.randomUUID(), endpoint))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.UNREACHABLE));
        assertThatThrownBy(() -> service.listStacks(UUID.randomUUID(), endpoint))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.UNREACHABLE));
    }

    @Test
    void hangingDaemonThrowsTimeout() throws IOException {
        Path socket = Files.createTempFile("shipir", ".sock");
        Files.delete(socket);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        Thread acceptor = Thread.ofVirtual().start(() -> {
            try {
                while (true) accepted.add(server.accept());
            } catch (IOException ignored) {
                // server closed
            }
        });
        ContainerService service = realService(Duration.ofMillis(500));

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.listContainers(UUID.randomUUID(), new DockerEndpoint.Local("unix://" + socket)))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        acceptor.interrupt();
        Files.deleteIfExists(socket);
    }
}
