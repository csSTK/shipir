package dev.bethge.shipir.host;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link VolumeBackupStore#pruneStalePartials}: removes leftover {@code .backup-*.part} files, never through symlinks. */
class VolumeBackupStorePartialsTest {

    @TempDir
    Path base;
    @TempDir
    Path outside;

    private VolumeBackupStore store;
    private final UUID host = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        store = new VolumeBackupStore(new VolumeBackupProperties(DataSize.ofMegabytes(1), "alpine:3", base, false, Duration.ofMinutes(1)));
    }

    private Path put(String volume, String name) throws Exception {
        Path dir = Files.createDirectories(base.resolve(host.toString()).resolve(volume));
        return Files.writeString(dir.resolve(name), "x");
    }

    @Test
    void removesPartialFilesButKeepsArchivesAndOtherFiles() throws Exception {
        Path part = put("data", ".backup-123456.part");
        Path archive = put("data", "data-20260101T000000Z.tar");
        Path other = put("data", "notes.part");
        Path otherVolumePart = put("logs", ".backup-9.part");

        assertThat(store.pruneStalePartials(Duration.ZERO)).isEqualTo(2);

        assertThat(part).doesNotExist();
        assertThat(otherVolumePart).doesNotExist();
        assertThat(archive).exists();
        assertThat(other).exists();
    }

    @Test
    void keepsPartialFilesYoungerThanTheMinimumAge() throws Exception {
        Path part = put("data", ".backup-1.part");

        assertThat(store.pruneStalePartials(Duration.ofHours(1))).isZero();

        assertThat(part).exists();
    }

    @Test
    void doesNothingWithoutStoreDirectory() {
        VolumeBackupStore empty = new VolumeBackupStore(new VolumeBackupProperties(DataSize.ofMegabytes(1), "alpine:3",
                base.resolve("missing"), false, Duration.ofMinutes(1)));

        assertThat(empty.pruneStalePartials(Duration.ZERO)).isZero();
    }

    @Test
    void neverFollowsSymlinks() throws Exception {
        Path target = Files.writeString(outside.resolve(".backup-evil.part"), "keep");
        Path outsideVolume = Files.createDirectories(outside.resolve("vol"));
        Path outsidePart = Files.writeString(outsideVolume.resolve(".backup-2.part"), "keep");
        Path hostDir = Files.createDirectories(base.resolve(host.toString()));
        Files.createSymbolicLink(hostDir.resolve("linked"), outsideVolume);
        Path dir = Files.createDirectories(hostDir.resolve("data"));
        Files.createSymbolicLink(dir.resolve(".backup-link.part"), target);

        assertThat(store.pruneStalePartials(Duration.ZERO)).isZero();

        assertThat(target).exists();
        assertThat(outsidePart).exists();
        assertThat(Files.isSymbolicLink(dir.resolve(".backup-link.part"))).isTrue();
    }
}
