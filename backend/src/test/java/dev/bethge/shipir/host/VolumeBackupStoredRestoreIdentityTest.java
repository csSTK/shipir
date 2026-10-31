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
        VolumeBackupStore store = new VolumeBackupStore(props);
        service = new VolumeBackupService(hosts, endpoints, archives, store, access, props, Clock.systemUTC());

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

    @Test
    void archiveReplacedBetweenTheTwoOpensIsRefusedAsInvalidArchive() {
        int[] opens = new int[1];
        openTwice(() -> {
            try {
                Path other = Files.writeString(file.resolveSibling("other.tmp"), "swapped-bytes-of-other-length");
                Files.move(other, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, opens);

        assertThatThrownBy(() -> service.restoreStored(hostId, VOLUME, NAME, false))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("INVALID_ARCHIVE"));
        assertThat(opens[0]).isEqualTo(1);
    }

    @Test
    void archiveRewrittenInPlaceWithTheSameSizeIsRefused() {
        int[] opens = new int[1];
        openTwice(() -> {
            try {
                Files.writeString(file, "VALIDATED-BYTES"); // same size, same file key
                Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, opens);

        assertThatThrownBy(() -> service.restoreStored(hostId, VOLUME, NAME, false))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("INVALID_ARCHIVE"));
        assertThat(opens[0]).isEqualTo(1);
    }
}
