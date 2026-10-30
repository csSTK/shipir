package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.ContainerFileWrite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validation of writeFiles runs before any Docker call, so these tests need no daemon (manager points nowhere). */
class ContainerFileWriteTest {

    private final DockerHostConnectionManager manager = new DockerHostConnectionManager(
            new DockerProperties("unix:///nonexistent.sock", Duration.ofSeconds(1), Duration.ofSeconds(1)));
    private final ContainerFileServiceImpl service = new ContainerFileServiceImpl(manager, null);
    private final DockerEndpoint endpoint = manager.localEndpoint();

    private void write(String dir, long max, ContainerFileWrite... files) {
        service.writeFiles(UUID.randomUUID(), endpoint, "c", dir, List.of(files), max);
    }

    private static ContainerFileWrite file(String path) {
        return new ContainerFileWrite(path, new byte[]{1}, 0644);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../x", "a/../../x", "/etc/x", "a/../x", "a//b", "./x", "x/.", "", "a\0b", "a/", "..", "a\\..\\x"})
    void rejectsMaliciousArchivePaths(String path) {
        assertThatThrownBy(() -> write("/data", 1000, file(path)))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_PATH));
    }

    @Test
    void rejectsDuplicatesAndRelativeDirectory() {
        assertThatThrownBy(() -> write("/data", 1000, file("a"), file("a")))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_PATH));
        assertThatThrownBy(() -> write("data", 1000, file("a")))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_PATH));
    }

    @Test
    void rejectsContentOverSizeLimit() {
        assertThatThrownBy(() -> write("/data", 4, new ContainerFileWrite("a", new byte[5], 0644)))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void rejectsOverlongPath() {
        assertThatThrownBy(() -> write("/data", 1000, file("a/" + "b".repeat(4100))))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_PATH));
    }

    @Test
    void recordRejectsNullPathAndContentAndCopiesContent() {
        assertThatThrownBy(() -> new ContainerFileWrite(null, new byte[0], 0644)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ContainerFileWrite("a", null, 0644)).isInstanceOf(NullPointerException.class);
        byte[] src = {1, 2};
        var w = new ContainerFileWrite("a", src, 0644);
        src[0] = 9;
        assertThat(w.content()).containsExactly(1, 2);
    }
}
