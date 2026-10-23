package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** No Docker needed: failing and hanging sockets. */
class DockerSystemServiceTest {

    @TempDir
    Path dir;

    private final List<SocketChannel> accepted = new ArrayList<>();
    private ServerSocketChannel server;

    @AfterEach
    void tearDown() throws IOException {
        for (SocketChannel c : accepted) c.close();
        if (server != null) server.close();
    }

    private DockerSystemService service(Duration connect, Duration read) {
        return new DockerSystemServiceImpl(
                new DockerHostConnectionManager(new DockerProperties("unix:///unused.sock", connect, read)));
    }

    @Test
    void pingOfNonexistentSocketReturnsUnreachableWithoutThrowing() {
        DockerSystemService service = service(Duration.ofSeconds(1), Duration.ofSeconds(1));
        var endpoint = new DockerEndpoint.Local("unix://" + dir.resolve("missing.sock"));

        assertThatCode(() -> service.ping(endpoint)).doesNotThrowAnyException();
        ConnectionTestResult result = service.ping(endpoint);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(DockerErrorCode.UNREACHABLE);
        assertThat(result.message()).isNotBlank();
        assertThat(result.dockerVersion()).isNull();
    }

    @Test
    void pingAppliesReadTimeoutWhenDaemonNeverAnswers() throws IOException {
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
        DockerSystemService service = service(Duration.ofSeconds(1), Duration.ofMillis(500));

        long start = System.nanoTime();
        ConnectionTestResult result = service.ping(new DockerEndpoint.Local("unix://" + socket));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(DockerErrorCode.TIMEOUT);
        assertThat(took).isLessThan(Duration.ofSeconds(5));
        acceptor.interrupt();
        Files.deleteIfExists(socket);
    }
}
