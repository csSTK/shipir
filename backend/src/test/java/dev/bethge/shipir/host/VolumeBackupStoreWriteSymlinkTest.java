package dev.bethge.shipir.host;

import dev.bethge.shipir.common.util.PathValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link VolumeBackupStore#write} and its retention never follow a symlinked host or volume directory or archive. */
class VolumeBackupStoreWriteSymlinkTest {

    @TempDir
    Path base;
    @TempDir
    Path outside;

    private VolumeBackupStore store;
    private final UUID host = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        store = new VolumeBackupStore(new VolumeBackupProperties(DataSize.ofMegabytes(1), "alpine:3", base, false, Duration.ofMinutes(1)), Clock.systemUTC());
    }

    private static ByteArrayInputStream content() {
        return new ByteArrayInputStream("data".getBytes());
    }

    private static long fileCount(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.count();
        }
    }

    @Test
    void writeRefusesAHostDirectorySymlinkedOutsideTheStore() throws Exception {
        Files.createSymbolicLink(base.resolve(host.toString()), outside);

        assertThatThrownBy(() -> store.write(host, "data", content(), Instant.now(), 3)).isInstanceOfAny(IOException.class, PathValidationException.class);

        assertThat(fileCount(outside)).isZero();
    }

    @Test
    void writeCreatesNothingBehindAHostDirectorySymlinkOutsideTheStore() throws Exception {
        Files.createSymbolicLink(base.resolve(host.toString()), outside);

        assertThatThrownBy(() -> store.write(host, "data", content(), Instant.now(), 3)).isInstanceOfAny(IOException.class, PathValidationException.class);

        assertThat(fileCount(outside)).as("no volume directory created through the symlink").isZero();
    }

    @Test
    void writeCreatesNothingBehindAHostDirectorySymlinkedToAnotherHostInsideTheStore() throws Exception {
        Path otherHost = Files.createDirectories(base.resolve(UUID.randomUUID().toString()));
        Files.createSymbolicLink(base.resolve(host.toString()), otherHost);

        assertThatThrownBy(() -> store.write(host, "data", content(), Instant.now(), 3)).isInstanceOfAny(IOException.class, PathValidationException.class);

        assertThat(fileCount(otherHost)).as("no volume directory created in the other host's tree").isZero();
    }

    @Test
    void writeRefusesADanglingHostDirectorySymlinkAndCreatesNoTarget() throws Exception {
        Path missing = outside.resolve("not-yet");
        Files.createSymbolicLink(base.resolve(host.toString()), missing);

        assertThatThrownBy(() -> store.write(host, "data", content(), Instant.now(), 3)).isInstanceOfAny(IOException.class, PathValidationException.class);

        assertThat(missing).doesNotExist();
    }

    @Test
    void writeRefusesAVolumeDirectorySymlinkedOutsideTheStore() throws Exception {
        Files.createDirectories(base.resolve(host.toString()));
        Files.createSymbolicLink(base.resolve(host.toString()).resolve("data"), outside);

        assertThatThrownBy(() -> store.write(host, "data", content(), Instant.now(), 3)).isInstanceOfAny(IOException.class, PathValidationException.class);

        assertThat(fileCount(outside)).isZero();
    }

    @Test
    void writeRefusesAVolumeDirectorySymlinkedToAnotherHostsVolumeInsideTheStore() throws Exception {
        UUID other = UUID.randomUUID();
        Path otherVolume = Files.createDirectories(base.resolve(other.toString()).resolve("data"));
        Files.createDirectories(base.resolve(host.toString()));
        Files.createSymbolicLink(base.resolve(host.toString()).resolve("data"), otherVolume);

        assertThatThrownBy(() -> store.write(host, "data", content(), Instant.now(), 3)).isInstanceOfAny(IOException.class, PathValidationException.class);

        assertThat(fileCount(otherVolume)).isZero();
    }

    @Test
    void writeStillWorksForRealDirectoriesAndPruneIgnoresSymlinkedArchives() throws Exception {
        Path dir = Files.createDirectories(base.resolve(host.toString()).resolve("data"));
        Path target = Files.writeString(outside.resolve("victim.tar"), "keep me");
        Files.createSymbolicLink(dir.resolve("data-20200101T000000Z.tar"), target);
        Files.writeString(dir.resolve("data-20200102T000000Z.tar"), "real-old");

        Path written = store.write(host, "data", content(), Instant.parse("2026-01-01T00:00:00Z"), 1);

        assertThat(written).exists();
        assertThat(target).hasContent("keep me");
        assertThat(dir.resolve("data-20200102T000000Z.tar")).doesNotExist();
        assertThat(Files.isSymbolicLink(dir.resolve("data-20200101T000000Z.tar"))).isTrue();
    }
}
