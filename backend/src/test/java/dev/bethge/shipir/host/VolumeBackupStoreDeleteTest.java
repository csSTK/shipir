package dev.bethge.shipir.host;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link VolumeBackupStore#deleteAll}: removes only archives of the exact name pattern, never through symlinks. */
class VolumeBackupStoreDeleteTest {

    @TempDir
    Path base;
    @TempDir
    Path outside;

    private VolumeBackupStore store;
    private final UUID host = UUID.randomUUID();
    private final UUID otherHost = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        store = new VolumeBackupStore(new VolumeBackupProperties(DataSize.ofMegabytes(1), "alpine:3", base, false, Duration.ofMinutes(1)));
    }

    private Path put(UUID h, String volume, String name) throws Exception {
        Path dir = Files.createDirectories(base.resolve(h.toString()).resolve(volume));
        return Files.writeString(dir.resolve(name), "x");
    }

    @Test
    void deletesAllArchivesAndTheEmptyVolumeDirectory() throws Exception {
        put(host, "data", "data-20260101T000000Z.tar");
        put(host, "data", "data-20260102T000000Z.tar");

        assertThat(store.deleteAll(host, "data")).isEqualTo(2);

        assertThat(base.resolve(host.toString()).resolve("data")).doesNotExist();
    }

    @Test
    void leavesOtherNamesOtherVolumesAndOtherHostsUntouchedAndKeepsNonEmptyDirectory() throws Exception {
        put(host, "data", "data-20260101T000000Z.tar");
        Path notes = put(host, "data", "notes.txt");
        Path partial = put(host, "data", ".backup-1.part");
        Path badName = put(host, "data", "data-2026.tar");
        Path foreignName = put(host, "data", "data2-20260101T000000Z.tar");
        Path otherVolume = put(host, "other", "other-20260101T000000Z.tar");
        Path otherHostFile = put(otherHost, "data", "data-20260101T000000Z.tar");

        assertThat(store.deleteAll(host, "data")).isEqualTo(1);

        assertThat(notes).exists();
        assertThat(partial).exists();
        assertThat(badName).exists();
        assertThat(foreignName).exists();
        assertThat(otherVolume).exists();
        assertThat(otherHostFile).exists();
    }

    @Test
    void doesNotDeleteThroughSymlinkedArchive() throws Exception {
        Path secret = Files.writeString(outside.resolve("secret.tar"), "keep me");
        Path dir = Files.createDirectories(base.resolve(host.toString()).resolve("data"));
        Path link = Files.createSymbolicLink(dir.resolve("data-20260101T000000Z.tar"), secret);

        assertThat(store.deleteAll(host, "data")).isZero();

        assertThat(secret).exists().hasContent("keep me");
        assertThat(Files.isSymbolicLink(link)).isTrue();
    }

    @Test
    void doesNotFollowSymlinkedVolumeDirectory() throws Exception {
        Path victim = Files.writeString(Files.createDirectories(outside.resolve("vol")).resolve("data-20260101T000000Z.tar"), "keep me");
        Files.createDirectories(base.resolve(host.toString()));
        Files.createSymbolicLink(base.resolve(host.toString()).resolve("data"), outside.resolve("vol"));

        assertThat(store.deleteAll(host, "data")).isZero();

        assertThat(victim).exists();
    }

    @Test
    void doesNotFollowSymlinkedHostDirectory() throws Exception {
        Path victim = Files.writeString(Files.createDirectories(outside.resolve("h").resolve("data")).resolve("data-20260101T000000Z.tar"), "keep me");
        Files.createSymbolicLink(base.resolve(host.toString()), outside.resolve("h"));

        assertThat(store.deleteAll(host, "data")).isZero();

        assertThat(victim).exists();
    }

    @Test
    void missingDirectoriesAndMaliciousVolumeNamesDeleteNothing() throws Exception {
        Path other = put(otherHost, "data", "data-20260101T000000Z.tar");

        assertThat(store.deleteAll(host, "data")).isZero();
        assertThat(store.deleteAll(host, "../" + otherHost + "/data")).isZero();
        assertThat(store.deleteAll(host, "..")).isZero();

        assertThat(other).exists();
    }

    @Test
    void retentionStillKeepsNewestAfterwards() throws Exception {
        store.write(host, "data", new ByteArrayInputStream("a".getBytes()), Instant.parse("2026-01-01T00:00:00Z"), 1);
        store.write(host, "data", new ByteArrayInputStream("b".getBytes()), Instant.parse("2026-01-02T00:00:00Z"), 1);
        assertThat(store.list(host, "data")).hasSize(1);

        store.deleteAll(host, "data");
        store.write(host, "data", new ByteArrayInputStream("c".getBytes()), Instant.parse("2026-01-03T00:00:00Z"), 1);

        assertThat(store.list(host, "data")).hasSize(1);
    }
}
