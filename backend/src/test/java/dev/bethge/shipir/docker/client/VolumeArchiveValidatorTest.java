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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The tar checks that keep a restore inside the volume root: malicious entries must be refused before extraction. */
class VolumeArchiveValidatorTest {

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

    private static void file(TarArchiveOutputStream tar, String name, String content) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry e = new TarArchiveEntry(name, true); // keep a leading slash as an attacker would
        e.setSize(data.length);
        tar.putArchiveEntry(e);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    private static void dir(TarArchiveOutputStream tar, String name) throws IOException {
        tar.putArchiveEntry(new TarArchiveEntry(name.endsWith("/") ? name : name + "/"));
        tar.closeArchiveEntry();
    }

    private static void symlink(TarArchiveOutputStream tar, String name, String target) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
        e.setLinkName(target);
        tar.putArchiveEntry(e);
        tar.closeArchiveEntry();
    }

    private static void hardlink(TarArchiveOutputStream tar, String name, String target) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name, TarArchiveEntry.LF_LINK);
        e.setLinkName(target);
        tar.putArchiveEntry(e);
        tar.closeArchiveEntry();
    }

    private static void validate(byte[] tar) {
        VolumeArchiveValidator.validate(new ByteArrayInputStream(tar), 1_000_000);
    }

    private static void assertRejected(byte[] tar) {
        assertThatThrownBy(() -> validate(tar))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_ARCHIVE));
    }

    @Test
    void acceptsArchiveShapedLikeABackup() throws Exception {
        byte[] ok = tar(t -> {
            dir(t, "./");
            file(t, "./a.txt", "hello");
            dir(t, "./sub");
            file(t, "./sub/b.txt", "nested");
            symlink(t, "./sub/up", "../a.txt");
            symlink(t, "./rel", "sub/b.txt");
            hardlink(t, "./hard", "a.txt");
        });
        assertThatCode(() -> validate(ok)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil", "./../evil", "a/../../evil", "sub/../../../etc/passwd", "..", "a/.."})
    void rejectsEntryEscapingTheRootWithDotDot(String name) throws Exception {
        assertRejected(tar(t -> file(t, name, "x")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/etc/passwd", "/rootfile", "//etc/cron.d/x"})
    void rejectsAbsoluteEntryNames(String name) throws Exception {
        assertRejected(tar(t -> file(t, name, "x")));
    }

    @Test
    void rejectsAbsoluteSymlinkTarget() throws Exception {
        assertRejected(tar(t -> symlink(t, "link", "/etc")));
    }

    @Test
    void rejectsSymlinkPointingAboveTheRoot() throws Exception {
        assertRejected(tar(t -> symlink(t, "link", "../outside")));
    }

    @Test
    void rejectsNestedSymlinkThatClimbsOutOfTheRoot() throws Exception {
        assertRejected(tar(t -> {
            dir(t, "sub");
            symlink(t, "sub/link", "../../outside");
        }));
    }

    @Test
    void rejectsHardLinkToOutsideOrAbsoluteTarget() throws Exception {
        assertRejected(tar(t -> hardlink(t, "h", "../x")));
        assertRejected(tar(t -> hardlink(t, "h", "/etc/passwd")));
    }

    @Test
    void rejectsEntryWrittenThroughAnEarlierSymlink() throws Exception {
        assertRejected(tar(t -> {
            dir(t, "real");
            symlink(t, "link", "real");
            file(t, "link/payload", "x");
        }));
    }

    @Test
    void rejectsDeviceAndFifoEntries() throws Exception {
        assertRejected(tar(t -> {
            TarArchiveEntry dev = new TarArchiveEntry("dev0", TarArchiveEntry.LF_CHR);
            t.putArchiveEntry(dev);
            t.closeArchiveEntry();
        }));
        assertRejected(tar(t -> {
            TarArchiveEntry fifo = new TarArchiveEntry("pipe", TarArchiveEntry.LF_FIFO);
            t.putArchiveEntry(fifo);
            t.closeArchiveEntry();
        }));
    }

    @Test
    void rejectsEmptyAndNonTarInput() {
        assertRejected(new byte[0]);
        assertRejected("this is definitely not a tar archive, just text".repeat(30).getBytes(StandardCharsets.UTF_8));
        assertRejected(new byte[]{0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, 3, 1, 2, 3});
    }

    @Test
    void rejectsArchiveWhoseContentExceedsTheLimit() throws Exception {
        byte[] big = tar(t -> file(t, "big.bin", "x".repeat(5000)));
        assertThatThrownBy(() -> VolumeArchiveValidator.validate(new ByteArrayInputStream(big), 2048))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void rejectsFileWrittenThroughAHardLinkToASymlink() throws Exception {
        assertRejected(tar(t -> {
            dir(t, "a");
            dir(t, "a/b");
            symlink(t, "a/b/s", "../../x");
            hardlink(t, "h", "a/b/s");
            file(t, "h/payload", "x");
        }));
    }

    @Test
    void rejectsLinkTargetThatPassesThroughAnEarlierSymlink() throws Exception {
        assertRejected(tar(t -> {
            dir(t, "sub");
            symlink(t, "s", "sub");
            hardlink(t, "h", "s/file");
        }));
        assertRejected(tar(t -> {
            dir(t, "sub");
            symlink(t, "s", "sub");
            symlink(t, "t", "s/../../x");
        }));
    }

    @Test
    void rejectsSymlinkChainThatClimbsOutViaDotSymlink() throws Exception {
        assertRejected(tar(t -> {
            symlink(t, "s", ".");
            symlink(t, "t", "s/s/../..");
        }));
    }

    @Test
    void acceptsSymlinkToSymlinkAndRegularHardLinks() throws Exception {
        assertThatCode(() -> validate(tar(t -> {
            dir(t, "sub");
            file(t, "sub/a", "x");
            hardlink(t, "sub/h", "sub/a");
            symlink(t, "s", "sub");
            symlink(t, "t", "s");
        }))).doesNotThrowAnyException();
    }

    private static void assertRejectedQuickly(byte[] tar) {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertRejected(tar));
    }

    @Test
    void rejectsVeryDeepEntryNameQuickly() throws Exception {
        String deep = "a/".repeat(300) + "f";
        assertRejectedQuickly(tar(t -> file(t, deep, "x")));
    }

    @Test
    void rejectsVeryDeepSymlinkAndHardLinkTargetsQuickly() throws Exception {
        String deep = "a/".repeat(300) + "f";
        assertRejectedQuickly(tar(t -> symlink(t, "l", deep)));
        assertRejectedQuickly(tar(t -> hardlink(t, "h", deep)));
    }

    @Test
    void rejectsVeryLongNamesAndTargetsQuickly() throws Exception {
        String huge = "a".repeat(20_000);
        assertRejectedQuickly(tar(t -> file(t, huge, "x")));
        assertRejectedQuickly(tar(t -> symlink(t, "l", huge)));
    }

    @Test
    void rejectsOverlongEntryNameWithFewSegments() throws Exception {
        assertRejected(tar(t -> file(t, "a".repeat(5000), "x")));
    }

    @Test
    void acceptsModeratelyDeepPaths() throws Exception {
        String deep = "d/".repeat(20);
        assertThatCode(() -> validate(tar(t -> {
            file(t, deep + "f.txt", "x");
            symlink(t, "link", deep + "f.txt");
            hardlink(t, "hard", deep + "f.txt");
        }))).doesNotThrowAnyException();
    }
}
