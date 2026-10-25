package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hostile build contexts (tar or tar.gz) must be refused before anything is sent to the daemon. */
class BuildContextValidationTest {

    private static final long LIMIT = 1024 * 1024;
    private final ImageServiceImpl service = new ImageServiceImpl(null);

    private interface TarBuilder {
        void build(TarArchiveOutputStream tar) throws IOException;
    }

    private static byte[] tar(TarBuilder builder) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            builder.build(tar);
        }
        return out.toByteArray();
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static void file(TarArchiveOutputStream tar, String name, byte[] data) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name, true);
        e.setSize(data.length);
        tar.putArchiveEntry(e);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    private static byte[] dockerfileTar() throws IOException {
        return tar(t -> file(t, "Dockerfile", "FROM busybox\nCMD [\"true\"]\n".getBytes(StandardCharsets.UTF_8)));
    }

    private void validate(byte[] archive) {
        service.validateBuildContext(new ByteArrayInputStream(archive), LIMIT);
    }

    private void assertRefused(byte[] archive, DockerErrorCode code) {
        assertThatThrownBy(() -> validate(archive)).isInstanceOfSatisfying(DockerConnectionException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void plainTarAndGzippedTarAreAccepted() throws Exception {
        assertThatCode(() -> validate(dockerfileTar())).doesNotThrowAnyException();
        assertThatCode(() -> validate(gzip(dockerfileTar()))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil", "a/../../evil", "/etc/passwd", "a/b/../../../x"})
    void traversalAndAbsoluteEntryNamesAreRefused(String name) throws Exception {
        assertRefused(tar(t -> file(t, name, new byte[]{1})), DockerErrorCode.INVALID_ARCHIVE);
        assertRefused(gzip(tar(t -> file(t, name, new byte[]{1}))), DockerErrorCode.INVALID_ARCHIVE);
    }

    @Test
    void symlinkLeavingTheContextIsRefused() throws Exception {
        byte[] archive = tar(t -> {
            TarArchiveEntry e = new TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK);
            e.setLinkName("../../etc/passwd");
            t.putArchiveEntry(e);
            t.closeArchiveEntry();
        });
        assertRefused(archive, DockerErrorCode.INVALID_ARCHIVE);
    }

    @Test
    void absoluteSymlinkTargetIsRefused() throws Exception {
        byte[] archive = tar(t -> {
            TarArchiveEntry e = new TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK);
            e.setLinkName("/etc/passwd");
            t.putArchiveEntry(e);
            t.closeArchiveEntry();
        });
        assertRefused(archive, DockerErrorCode.INVALID_ARCHIVE);
    }

    @Test
    void deviceNodesAreRefused() throws Exception {
        byte[] archive = tar(t -> {
            TarArchiveEntry e = new TarArchiveEntry("dev", TarArchiveEntry.LF_CHR);
            t.putArchiveEntry(e);
            t.closeArchiveEntry();
        });
        assertRefused(archive, DockerErrorCode.INVALID_ARCHIVE);
    }

    @Test
    void tooDeepAndTooLongNamesAreRefused() throws Exception {
        assertRefused(tar(t -> file(t, "a/".repeat(200) + "f", new byte[]{1})), DockerErrorCode.INVALID_ARCHIVE);
        assertRefused(tar(t -> file(t, "x".repeat(5000), new byte[]{1})), DockerErrorCode.INVALID_ARCHIVE);
    }

    @Test
    void emptyAndNonTarInputIsRefused() throws Exception {
        assertRefused(new byte[0], DockerErrorCode.INVALID_ARCHIVE);
        assertRefused("this is not a tar archive, just text".repeat(40).getBytes(StandardCharsets.UTF_8),
                DockerErrorCode.INVALID_ARCHIVE);
    }

    @Test
    void contentOverTheLimitIsRefusedWhileReading() throws Exception {
        byte[] archive = tar(t -> file(t, "big.bin", new byte[(int) LIMIT + 1]));
        assertRefused(archive, DockerErrorCode.SIZE_LIMIT_EXCEEDED);
    }

    @Test
    void gzipBombIsStoppedByTheLimitOnTheExpandedSize() throws Exception {
        // 8 MB of zeros gzip to a few KB: small upload, large expansion
        byte[] archive = gzip(tar(t -> file(t, "zeros.bin", new byte[8 * 1024 * 1024])));
        assertThat(archive.length).isLessThan((int) LIMIT);
        assertRefused(archive, DockerErrorCode.SIZE_LIMIT_EXCEEDED);
    }
}
