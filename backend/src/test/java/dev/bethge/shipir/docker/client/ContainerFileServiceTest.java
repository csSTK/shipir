package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.ContainerFileEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    // --- exec fallback (shipr-p4vd) ---

    private static final UUID HOST = UUID.randomUUID();

    private static byte[] dirTar() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            tar.putArchiveEntry(new TarArchiveEntry("data/"));
            tar.closeArchiveEntry();
            TarArchiveEntry file = new TarArchiveEntry("data/big.bin");
            file.setSize(5000);
            tar.putArchiveEntry(file);
            tar.write(new byte[5000]);
            tar.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    private static ContainerFileServiceImpl serviceWith(ContainerDirectoryExecLister lister) {
        var manager = new DockerHostConnectionManager(
                new DockerProperties("unix:///nonexistent.sock", Duration.ofSeconds(1), Duration.ofSeconds(1)));
        return new ContainerFileServiceImpl(manager, lister) {
            @Override
            InputStream openArchive(UUID hostId, DockerEndpoint endpoint, String containerId, String path) {
                try {
                    return new ByteArrayInputStream(dirTar());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static ContainerDirectoryExecLister lister(java.util.function.Supplier<List<ContainerFileEntry>> result,
                                                      String[] seenDir) {
        return new ContainerDirectoryExecLister((DockerHostConnectionManager) null) {
            @Override
            List<ContainerFileEntry> list(UUID h, DockerEndpoint e, String c, String dir) {
                seenDir[0] = dir;
                return result.get();
            }
        };
    }

    @Test
    void readFileWithLongMaxValueLimitStillReadsTheFile() throws Exception {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        var service = serviceWithArchive(singleFileTar("app.conf", payload));

        assertThat(service.readFile(HOST, null, "c", "/etc/app.conf", Long.MAX_VALUE)).isEqualTo(payload);
    }

    @Test
    void readFileStillEnforcesALimitBelowTheFileSize() throws Exception {
        var service = serviceWithArchive(singleFileTar("app.conf", new byte[5]));

        assertThatThrownBy(() -> service.readFile(HOST, null, "c", "/etc/app.conf", 4))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.SIZE_LIMIT_EXCEEDED));
    }

    private static byte[] singleFileTar(String name, byte[] content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            TarArchiveEntry file = new TarArchiveEntry(name);
            file.setSize(content.length);
            tar.putArchiveEntry(file);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    private static ContainerFileServiceImpl serviceWithArchive(byte[] archive) {
        var manager = new DockerHostConnectionManager(
                new DockerProperties("unix:///nonexistent.sock", Duration.ofSeconds(1), Duration.ofSeconds(1)));
        return new ContainerFileServiceImpl(manager, lister(List::of, new String[1])) {
            @Override
            InputStream openArchive(UUID hostId, DockerEndpoint endpoint, String containerId, String path) {
                return new ByteArrayInputStream(archive);
            }
        };
    }

    @Test
    void archiveOverScanLimitFallsBackToExecListing() {
        String[] seen = new String[1];
        var entry = new ContainerFileEntry("x", "/data/x", ContainerFileEntry.Type.FILE, 0, null, 0, null);
        var service = serviceWith(lister(() -> List.of(entry), seen));

        assertThat(service.list(HOST, null, "c", "/data/../data/", 100)).containsExactly(entry);
        assertThat(seen[0]).isEqualTo("/data"); // normalised path, not the raw input
    }

    @Test
    void archiveWithinScanLimitDoesNotUseExec() {
        String[] seen = new String[1];
        var service = serviceWith(lister(() -> {
            throw new AssertionError("exec must not run");
        }, seen));

        assertThat(service.list(HOST, null, "c", "/data", 1_000_000)).extracting(ContainerFileEntry::name)
                .containsExactly("big.bin");
        assertThat(seen[0]).isNull();
    }

    @Test
    void execFailureKeepsDirectoryTooLarge() {
        var service = serviceWith(lister(() -> {
            throw new DockerConnectionException(DockerErrorCode.DIRECTORY_TOO_LARGE, "x", null);
        }, new String[1]));

        assertThatThrownBy(() -> service.list(HOST, null, "c", "/data", 100)).isInstanceOfSatisfying(
                DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.DIRECTORY_TOO_LARGE));
    }

    @Test
    void execCommandIsAnArgumentListWithAnAbsoluteDirectory() {
        assertThat(ContainerDirectoryExecLister.command("/usr", List.of("-type", "d")))
                .containsExactly("find", "/usr/", "-mindepth", "1", "-maxdepth", "1", "-type", "d", "-print0");
        assertThat(ContainerDirectoryExecLister.command("/", List.of()).get(1)).isEqualTo("/");
        // hostile input is neutralised by normalizePath before it can reach the command
        assertThat(ContainerDirectoryExecLister.command(ContainerFileServiceImpl.normalizePath("/a/../-x"), List.of()).get(1))
                .isEqualTo("/-x/");
        assertThat(ContainerFileServiceImpl.normalizePath("/../../etc")).isEqualTo("/etc");
    }

    @Test
    void parseKeepsNewlineAndOptionLikeNamesAsPlainNames() {
        byte[] out = "/d/a\nb\0/d/-rf\0/d/--help\0/d/ok\0".getBytes(StandardCharsets.UTF_8);
        assertThat(ContainerDirectoryExecLister.parse("/d", out)).containsExactly("a\nb", "-rf", "--help", "ok");
    }

    @Test
    void parseIgnoresUntrustedOddOutput() {
        byte[] out = "/d/../etc\0/other/x\0/d/a/b\0/d/\0/d/..\0/d/.\0/d/fine\0garbage".getBytes(StandardCharsets.UTF_8);
        assertThat(ContainerDirectoryExecLister.parse("/d", out)).containsExactly("fine");
    }

    @Test
    void parseAtRootStripsSinglePrefixSlash() {
        assertThat(ContainerDirectoryExecLister.parse("/", "/etc\0/usr\0".getBytes(StandardCharsets.UTF_8)))
                .containsExactly("etc", "usr");
    }

    @Test
    void parseEnforcesTheEntryCap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= ContainerDirectoryExecLister.MAX_ENTRIES; i++) sb.append("/d/f").append(i).append('\0');
        assertThatThrownBy(() -> ContainerDirectoryExecLister.parse("/d", sb.toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.DIRECTORY_TOO_LARGE));
    }

    @Test
    void entriesGetTypesFromTheDirectoryAndSymlinkSets() {
        Set<String> all = new LinkedHashSet<>(List.of("d", "l", "f"));
        var entries = ContainerDirectoryExecLister.toEntries("/p", all, Set.of("d"), Set.of("l"), Set.of("f"));
        assertThat(entries).extracting(ContainerFileEntry::type).containsExactly(
                ContainerFileEntry.Type.DIRECTORY, ContainerFileEntry.Type.SYMLINK, ContainerFileEntry.Type.FILE);
        assertThat(entries.get(2).path()).isEqualTo("/p/f");
    }

    @Test
    void outputOverTheCapIsDroppedAndTheCallbackClosed() {
        var collector = new ContainerDirectoryExecLister.OutputCollector(10);
        collector.onNext(new com.github.dockerjava.api.model.Frame(com.github.dockerjava.api.model.StreamType.STDOUT, new byte[6]));
        assertThat(collector.overflowed()).isFalse();
        assertThat(collector.isClosed()).isFalse();
        collector.onNext(new com.github.dockerjava.api.model.Frame(com.github.dockerjava.api.model.StreamType.STDOUT, new byte[6]));
        assertThat(collector.overflowed()).isTrue();
        assertThat(collector.isClosed()).isTrue();
        assertThat(collector.bytes()).hasSize(6);
    }

    @Test
    void stderrFramesAreIgnored() {
        var collector = new ContainerDirectoryExecLister.OutputCollector(10);
        collector.onNext(new com.github.dockerjava.api.model.Frame(com.github.dockerjava.api.model.StreamType.STDERR, new byte[50]));
        assertThat(collector.overflowed()).isFalse();
        assertThat(collector.bytes()).isEmpty();
    }

    @Test
    void nonZeroOrMissingExitCodeIsDirectoryTooLarge() {
        // 127 (no find) is exec-unavailable, covered in ContainerDirectoryExecListerTest
        for (Long bad : new Long[]{1L, null}) {
            assertThatThrownBy(() -> ContainerDirectoryExecLister.checkExit(bad)).isInstanceOfSatisfying(
                    DockerConnectionException.class, e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.DIRECTORY_TOO_LARGE));
        }
        ContainerDirectoryExecLister.checkExit(0L);
    }
}
