package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerFileServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/etc", "/etc/", "//etc///passwd", "/a/./b", "/a/b/..", "/../../etc", "/a/../../b"})
    void normalisesAbsolutePaths(String path) {
        String result = ContainerFileServiceImpl.normalizePath(path);
        assertThat(result).startsWith("/").doesNotContain("//", "/./", "..");
    }

    @Test
    void normalisationResults() {
        assertThat(ContainerFileServiceImpl.normalizePath("//etc///passwd")).isEqualTo("/etc/passwd");
        assertThat(ContainerFileServiceImpl.normalizePath("/a/b/..")).isEqualTo("/a");
        assertThat(ContainerFileServiceImpl.normalizePath("/../../etc/")).isEqualTo("/etc");
        assertThat(ContainerFileServiceImpl.normalizePath("/")).isEqualTo("/");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "etc/passwd", "./x", "../x", "/etc/pass\0wd"})
    void rejectsRelativeEmptyAndNulPaths(String path) {
        assertThatThrownBy(() -> ContainerFileServiceImpl.normalizePath(path))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_PATH));
    }

    @Test
    void rejectsOverlongPath() {
        assertThatThrownBy(() -> ContainerFileServiceImpl.normalizePath("/" + "a".repeat(5000)))
                .isInstanceOf(DockerConnectionException.class);
    }

    @Test
    void limitedStreamFailsOnFirstByteOverTheLimit() throws Exception {
        var in = new ContainerFileServiceImpl.LimitedInputStream(new ByteArrayInputStream(new byte[10]), 8);
        byte[] buf = new byte[4];
        assertThat(in.read(buf)).isEqualTo(4);
        assertThat(in.read(buf)).isEqualTo(4);
        assertThatThrownBy(() -> in.read(buf)).isInstanceOfSatisfying(DockerConnectionException.class,
                e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.SIZE_LIMIT_EXCEEDED));
        assertThat(in.exceeded()).isTrue();
    }

    @Test
    void limitedStreamAllowsExactlyTheLimit() throws Exception {
        var in = new ContainerFileServiceImpl.LimitedInputStream(new ByteArrayInputStream(new byte[8]), 8);
        assertThat(in.readAllBytes()).hasSize(8);
        assertThat(in.exceeded()).isFalse();
    }
}
