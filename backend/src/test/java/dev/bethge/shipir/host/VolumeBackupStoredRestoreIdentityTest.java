package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.VolumeArchiveService;
import dev.bethge.shipir.permission.CurrentAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The stored archive is opened twice by the restore pipeline (validate, then stream). The second open must be the
 * same file as the first, otherwise the validated bytes are not the extracted bytes.
 */
class VolumeBackupStoredRestoreIdentityTest {

    private static final String VOLUME = "data";
    private static final String NAME = VOLUME + "-20260102T030405Z.tar";

    @TempDir
    Path base;

    private final UUID hostId = UUID.randomUUID();
    private VolumeArchiveService archives;
    private VolumeBackupService service;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        DockerHost host = mock(DockerHost.class);
        when(host.getId()).thenReturn(hostId);
        DockerHostRepository hosts = mock(DockerHostRepository.class);
        when(hosts.findById(hostId)).thenReturn(Optional.of(host));
        HostEndpointResolver endpoints = mock(HostEndpointResolver.class);
        DockerEndpoint endpoint = new DockerEndpoint.Local("unix:///unused");
        when(endpoints.endpointOf(any())).thenReturn(endpoint);
        CurrentAccess access = mock(CurrentAccess.class);
        when(access.canView(hostId)).thenReturn(true);
        when(access.canOperate(hostId)).thenReturn(true);
        archives = mock(VolumeArchiveService.class);

        VolumeBackupProperties props = new VolumeBackupProperties(DataSize.ofMegabytes(10), "alpine:3", base, false, Duration.ofMinutes(1));
        VolumeBackupStore store = new VolumeBackupStore(props, Clock.systemUTC());
        service = new VolumeBackupService(hosts, endpoints, archives, store, access, props, Clock.systemUTC(), mock(SystemManagedGuard.class));

        Path dir = Files.createDirectories(base.resolve(hostId.toString()).resolve(VOLUME));
        file = dir.resolve(NAME);
        Files.writeString(file, "validated-bytes");
    }

    /** Makes the mocked archive service open the source twice, running {@code between} after the first open. */
    private void openTwice(Runnable between, int[] opens) {
        doAnswer(inv -> {
            VolumeArchiveService.ArchiveSource source = inv.getArgument(4);
            try (InputStream first = source.open()) {
                first.readAllBytes();
                opens[0]++;
            }
            between.run();
            try (InputStream second = source.open()) {
                second.readAllBytes();
                opens[0]++;
            }
            return null;
        }).when(archives).restore(eq(hostId), any(), eq(VOLUME), anyString(), any(), anyLong(), anyBoolean());
    }

    @Test
    void unchangedArchiveIsOpenedTwiceAndRestored() {
        int[] opens = new int[1];
        openTwice(() -> { }, opens);

        service.restoreStored(hostId, VOLUME, NAME, false);

        assertThat(opens[0]).isEqualTo(2);
    }

    /** Both opens of the pipeline must see the bytes of the file that was opened first, whatever happens to the path. */
    private static String drain(InputStream in) throws IOException {
        return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void recordBothOpens(Runnable between, java.util.List<String> seen) {
        doAnswer(inv -> {
            VolumeArchiveService.ArchiveSource source = inv.getArgument(4);
            try (InputStream first = source.open()) {
                seen.add(drain(first));
            }
            between.run();
            try (InputStream second = source.open()) {
                seen.add(drain(second));
            }
            return null;
        }).when(archives).restore(eq(hostId), any(), eq(VOLUME), anyString(), any(), anyLong(), anyBoolean());
    }

    @Test
    void archiveReplacedOnDiskDuringTheRestoreIsNeverExtracted() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        recordBothOpens(() -> {
            try {
                Path other = Files.writeString(file.resolveSibling("other.tmp"), "swapped-bytes-of-other-length");
                Files.move(other, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, seen);

        service.restoreStored(hostId, VOLUME, NAME, false);

        assertThat(seen).containsExactly("validated-bytes", "validated-bytes");
    }

    @Test
    void archiveRewrittenInPlaceDuringTheRestoreIsNeverExtracted() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        recordBothOpens(() -> {
            try {
                Files.writeString(file, "VALIDATED-BYTES"); // same size, same file key
                Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, seen);

        service.restoreStored(hostId, VOLUME, NAME, false);

        assertThat(seen).containsExactly("validated-bytes", "validated-bytes");
    }

    @Test
    void restoreRunsOnAnOwnerOnlyCopyThatIsDeletedAfterSuccessAndAfterFailure() throws Exception {
        java.util.List<Path> copies = new java.util.ArrayList<>();
        doAnswer(inv -> {
            VolumeArchiveService.ArchiveSource source = inv.getArgument(4);
            try (InputStream in = source.open()) {
                in.readAllBytes();
            }
            Path copy = onlyNewCopy(copies);
            copies.add(copy);
            assertThat(Files.readString(copy)).isEqualTo("validated-bytes");
            if (Files.getFileStore(copy).supportsFileAttributeView("posix")) {
                assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(copy)))
                        .isEqualTo("rw-------");
            }
            return null;
        }).when(archives).restore(eq(hostId), any(), eq(VOLUME), anyString(), any(), anyLong(), anyBoolean());
        service.restoreStored(hostId, VOLUME, NAME, false);
        assertThat(copies).hasSize(1);
        assertThat(copies.get(0)).doesNotExist();

        doAnswer(inv -> {
            copies.add(onlyNewCopy(copies));
            throw new dev.bethge.shipir.docker.DockerConnectionException(dev.bethge.shipir.docker.DockerErrorCode.INVALID_ARCHIVE, "bad", null);
        }).when(archives).restore(eq(hostId), any(), eq(VOLUME), anyString(), any(), anyLong(), anyBoolean());
        assertThatThrownBy(() -> service.restoreStored(hostId, VOLUME, NAME, false)).isInstanceOf(HostException.class);
        assertThat(copies).hasSize(2);
        assertThat(copies.get(1)).doesNotExist();
    }

    /** The shipir-restore-* file in the shared tmpdir that is not one of {@code known} (parallel tests only add other names' files, ours is the one holding our bytes). */
    private static Path onlyNewCopy(java.util.List<Path> known) throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var files = Files.list(tmp)) {
            var candidates = files.filter(p -> p.getFileName().toString().startsWith("shipir-restore-"))
                    .filter(p -> !known.contains(p)).filter(p -> {
                        try {
                            return Files.readString(p).equals("validated-bytes");
                        } catch (IOException e) {
                            return false;
                        }
                    }).toList();
            assertThat(candidates).hasSize(1);
            return candidates.get(0);
        }
    }

    @Test
    void copyPrivatelyDoesNotFollowASymlink() throws Exception {
        Path target = Files.writeString(base.resolve("secret.tar"), "secret");
        Path link = Files.createSymbolicLink(base.resolve("link.tar"), target);

        assertThatThrownBy(() -> VolumeBackupService.copyPrivately(link, 1024))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("STORED_BACKUP_NOT_FOUND"));
    }

    @Test
    void copyPrivatelyReportsAMissingFileAsNotFound() {
        assertThatThrownBy(() -> VolumeBackupService.copyPrivately(base.resolve("gone.tar"), 1024))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("STORED_BACKUP_NOT_FOUND"));
    }

    @Test
    void copyPrivatelyDeletesItsTempFileWhenTheFileIsTooLarge() throws Exception {
        Path big = Files.write(base.resolve("big.tar"), new byte[100]);
        long before = countCopies(Path.of(System.getProperty("java.io.tmpdir")));

        assertThatThrownBy(() -> VolumeBackupService.copyPrivately(big, 50))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.status().value()).isEqualTo(413));

        assertThat(countCopies(Path.of(System.getProperty("java.io.tmpdir")))).isEqualTo(before);
    }

    @Test
    void copyPrivatelyDeletesItsTempFileOnAMidCopyReadError() throws Exception {
        // a directory can be opened for reading on some platforms but fails on read: use it as the failing source
        Path dir = Files.createDirectory(base.resolve("d.tar"));
        long before = countCopies(Path.of(System.getProperty("java.io.tmpdir")));

        assertThatThrownBy(() -> VolumeBackupService.copyPrivately(dir, 50)).isInstanceOf(HostException.class);

        assertThat(countCopies(Path.of(System.getProperty("java.io.tmpdir")))).isEqualTo(before);
    }

    @Test
    void oversizedStoredArchiveIsRefusedAsTooLarge() throws Exception {
        // limit is 10 MB
        try (var out = Files.newOutputStream(file)) {
            out.write(new byte[10 * 1024 * 1024 + 1]);
        }
        assertThatThrownBy(() -> service.restoreStored(hostId, VOLUME, NAME, false))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.status().value()).isEqualTo(413));
    }

    private static long countCopies(Path tmp) throws IOException {
        try (var files = Files.list(tmp)) {
            return files.filter(p -> p.getFileName().toString().startsWith("shipir-restore-")).count();
        }
    }
}
