package dev.bethge.shipir.host;

import dev.bethge.shipir.host.dto.StoredVolumeBackup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Read side of {@link VolumeBackupStore}: listing and resolving one stored archive, with malicious names. */
class VolumeBackupStoreReadTest {

    private static final String OLD = "data-20260101T000000Z.tar";
    private static final String NEW = "data-20260102T030405Z.tar";

    @TempDir
    Path base;

    private VolumeBackupStore store;
    private final UUID host = UUID.randomUUID();
    private final UUID otherHost = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        store = new VolumeBackupStore(new VolumeBackupProperties(DataSize.ofMegabytes(1), "alpine:3", base, false, Duration.ofMinutes(1)), Clock.systemUTC());
        put(host, "data", OLD, "old");
        put(host, "data", NEW, "newer!");
        put(host, "other", "other-20260101T000000Z.tar", "x");
        put(otherHost, "data", "data-20260103T000000Z.tar", "secret-of-other-host");
    }

    private Path put(UUID h, String volume, String name, String content) throws Exception {
        Path dir = Files.createDirectories(base.resolve(h.toString()).resolve(volume));
        return Files.writeString(dir.resolve(name), content);
    }

    @Test
    void listsOnlyArchivesOfThatHostAndVolumeNewestFirstWithSizeAndStampTime() throws Exception {
        Files.writeString(base.resolve(host.toString()).resolve("data").resolve("notes.txt"), "ignored");
        Files.writeString(base.resolve(host.toString()).resolve("data").resolve(".backup-1.part"), "partial");
        Files.writeString(base.resolve(host.toString()).resolve("data").resolve("data-2026.tar"), "badname");

        List<StoredVolumeBackup> list = store.list(host, "data");

        assertThat(list).extracting(StoredVolumeBackup::name).containsExactly(NEW, OLD);
        assertThat(list.get(0).sizeBytes()).isEqualTo(6);
        assertThat(list.get(0).createdAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
    }

    @Test
    void listIsEmptyWhenNothingWasStoredYet() {
        assertThat(store.list(host, "unknown")).isEmpty();
        assertThat(store.list(UUID.randomUUID(), "data")).isEmpty();
    }

    @Test
    void listIsEmptyWhenTheStoreDirectoryDoesNotExist() {
        VolumeBackupStore empty = new VolumeBackupStore(new VolumeBackupProperties(DataSize.ofMegabytes(1), "alpine:3",
                base.resolve("missing"), false, Duration.ofMinutes(1)), Clock.systemUTC());
        assertThat(empty.list(host, "data")).isEmpty();
    }

    @Test
    void opensAStoredArchive() throws Exception {
        try (var in = store.open(host, "data", NEW).orElseThrow()) {
            assertThat(new String(in.readAllBytes())).isEqualTo(Files.readString(base.resolve(host.toString()).resolve("data").resolve(NEW)));
        }
    }

    @Test
    void openOfAMissingArchiveIsEmpty() throws Exception {
        assertThat(store.open(host, "data", "data-20250101T000000Z.tar")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../data/data-20260101T000000Z.tar", "..", "/etc/passwd", "%2e%2e%2fpasswd", "data-20260101T000000Z.tar/",
            "data-20260101T000000Z.tar.part", "data-20260101T000000Z.TAR", "sub/data-20260101T000000Z.tar", "..\\x.tar",
            "data-2026.tar", "other-20260101T000000Z.tar", "", " ", "data-20260101T000000Z.tar\0"})
    void openRejectsNamesThatAreNotExactlyTheStoredPattern(String name) throws Exception {
        assertThatThrownBy(() -> store.open(host, "data", name))
                .isInstanceOfSatisfying(HostException.class, e -> assertThat(e.code()).isEqualTo("INVALID_RESOURCE_ID"));
    }

    @Test
    void openCannotReachAnotherHostsArchiveByName() throws Exception {
        // same name pattern, but the file only exists under the other host
        assertThat(store.open(host, "data", "data-20260103T000000Z.tar")).isEmpty();
    }

    @Test
    void openCannotReachAnotherVolumesArchive() throws Exception {
        assertThatThrownBy(() -> store.open(host, "other", NEW)).isInstanceOf(HostException.class);
        assertThatThrownBy(() -> store.open(host, "other", "data-20260101T000000Z.tar")).isInstanceOf(HostException.class);
    }

    @Test
    void openRejectsASymlinkedArchiveEvenWhenItPointsInsideTheStore() throws Exception {
        Path link = base.resolve(host.toString()).resolve("data").resolve("data-20260105T000000Z.tar");
        Files.createSymbolicLink(link, base.resolve(otherHost.toString()).resolve("data").resolve("data-20260103T000000Z.tar"));
        assertThat(store.open(host, "data", link.getFileName().toString())).isEmpty();
        assertThat(store.list(host, "data")).extracting(StoredVolumeBackup::name).doesNotContain(link.getFileName().toString());
    }

    @Test
    void openRejectsASymlinkedArchiveThatPointsOutsideTheStore() throws Exception {
        Path outside = Files.writeString(Files.createTempFile("outside", ".tar"), "outside");
        Path link = base.resolve(host.toString()).resolve("data").resolve("data-20260106T000000Z.tar");
        Files.createSymbolicLink(link, outside);
        assertThat(store.open(host, "data", link.getFileName().toString())).isEmpty();
    }

    @Test
    void openRejectsAVolumeDirectorySymlinkedToAnotherHost() throws Exception {
        UUID third = UUID.randomUUID();
        Files.createDirectories(base.resolve(third.toString()));
        Files.createSymbolicLink(base.resolve(third.toString()).resolve("data"), base.resolve(otherHost.toString()).resolve("data"));
        assertThat(store.open(third, "data", "data-20260103T000000Z.tar")).isEmpty();
        assertThat(store.list(third, "data")).isEmpty();
    }

    @Test
    void listIsEmptyWhenTheHostDirectoryIsASymlinkToAnotherHost() throws Exception {
        UUID third = UUID.randomUUID();
        Files.createSymbolicLink(base.resolve(third.toString()), base.resolve(otherHost.toString()));

        assertThat(store.list(third, "data")).isEmpty();
    }

    @Test
    void listIsEmptyWhenTheVolumeDirectoryIsASymlinkToAnotherHostsVolume() throws Exception {
        UUID third = UUID.randomUUID();
        Files.createDirectories(base.resolve(third.toString()));
        Files.createSymbolicLink(base.resolve(third.toString()).resolve("data"), base.resolve(otherHost.toString()).resolve("data"));

        assertThat(store.list(third, "data")).isEmpty();
    }
}
