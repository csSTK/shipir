package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The context cap holds while streaming, even when the declared size is a lie. */
class ImageBuildSpoolTest {

    /** Reports 10 bytes but delivers 5000. */
    private static MultipartFile lyingAbout(byte[] data) {
        return new MockMultipartFile("context", "c.tar", "application/x-tar", data) {
            @Override
            public long getSize() {
                return 10;
            }
        };
    }

    @Test
    void streamingPastTheLimitIsRefusedDespiteSmallDeclaredSize(@TempDir Path dir) {
        assertThatThrownBy(() -> ImageBuildService.spool(lyingAbout(new byte[5000]), dir.resolve("c.tar"), 1024))
                .isInstanceOfSatisfying(HostException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(e.code()).isEqualTo("SIZE_LIMIT_EXCEEDED");
                });
    }

    @Test
    void contextWithinTheLimitIsCopiedExactly(@TempDir Path dir) throws IOException {
        byte[] data = new byte[1024];
        java.util.Arrays.fill(data, (byte) 7);
        ImageBuildService.spool(new MockMultipartFile("context", data), dir.resolve("c.tar"), 1024);
        assertThat(Files.readAllBytes(dir.resolve("c.tar"))).isEqualTo(data);
    }
}
