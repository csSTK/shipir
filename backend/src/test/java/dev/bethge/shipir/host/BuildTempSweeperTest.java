package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** A crash between spooling a build context and the worker's cleanup leaves {@code build-*} directories behind. */
class BuildTempSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    private static BuildTempSweeper sweeper(Path root) {
        return new BuildTempSweeper(
                new ImageBuildProperties(DataSize.ofMegabytes(200), Duration.ofMinutes(30), root.toString()),
                Clock.fixed(NOW, Clock.systemUTC().getZone()));
    }

    private static Path dirWithAge(Path root, String name, Duration age) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("context.tar"), "tar");
        Files.setLastModifiedTime(dir, FileTime.from(NOW.minus(age)));
        return dir;
    }

    @Test
    void deletesStaleBuildDirectoryWithContentsAndKeepsFreshOne(@TempDir Path root) throws IOException {
        Path stale = dirWithAge(root, "build-stale", Duration.ofHours(7));
        Path fresh = dirWithAge(root, "build-fresh", Duration.ofHours(1));

        sweeper(root).sweepDirectory(root, NOW);

        assertThat(stale).doesNotExist();
        assertThat(fresh).isDirectory();
        assertThat(fresh.resolve("context.tar")).exists();
    }

    @Test
    void ignoresDirectoriesNotNamedBuildEvenWhenStale(@TempDir Path root) throws IOException {
        Path other = dirWithAge(root, "unrelated-old", Duration.ofDays(3));

        sweeper(root).sweepDirectory(root, NOW);

        assertThat(other).isDirectory();
    }

    @Test
    void neverFollowsSymlinkNamedBuildAndKeepsItsTarget(@TempDir Path root, @TempDir Path outside) throws IOException {
        Path target = Files.writeString(outside.resolve("precious.txt"), "keep");
        Files.setLastModifiedTime(outside, FileTime.from(NOW.minus(Duration.ofDays(3))));
        Path link = Files.createSymbolicLink(root.resolve("build-link"), outside);

        sweeper(root).sweepDirectory(root, NOW);

        assertThat(target).exists();
        assertThat(outside).isDirectory();
        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void keepsRegularFileNamedBuild(@TempDir Path root) throws IOException {
        Path file = Files.writeString(root.resolve("build-file"), "x");
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(Duration.ofDays(3))));

        sweeper(root).sweepDirectory(root, NOW);

        assertThat(file).exists();
    }

    @Test
    void missingRootIsNotAnError(@TempDir Path base) {
        sweeper(base.resolve("does-not-exist")).sweepDirectory(base.resolve("does-not-exist"), NOW);

        assertThat(base.resolve("does-not-exist")).doesNotExist();
    }

    @Test
    void blankTempDirSweepsStaleShipirDirsInSystemTempAndKeepsForeignBuildDirs(@TempDir Path systemTemp) throws IOException {
        Path stale = dirWithAge(systemTemp, "shipir-build-1234", Duration.ofDays(2));
        Path fresh = dirWithAge(systemTemp, "shipir-build-5678", Duration.ofHours(1));
        Path foreign = dirWithAge(systemTemp, "build-9999", Duration.ofDays(2));
        BuildTempSweeper unconfigured = new BuildTempSweeper(
                new ImageBuildProperties(DataSize.ofMegabytes(200), Duration.ofMinutes(30), ""),
                Clock.fixed(NOW, Clock.systemUTC().getZone()));
        String previous = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", systemTemp.toString());
        try {
            unconfigured.sweep();
        } finally {
            System.setProperty("java.io.tmpdir", previous);
        }

        assertThat(stale).doesNotExist();
        assertThat(fresh).isDirectory();
        assertThat(foreign).isDirectory();
    }

    @Test
    void configuredDirSweepsLegacyBuildPrefixAndShipirPrefix(@TempDir Path root) throws IOException {
        Path legacy = dirWithAge(root, "build-legacy", Duration.ofDays(2));
        Path current = dirWithAge(root, "shipir-build-current", Duration.ofDays(2));

        sweeper(root).sweepDirectory(root, NOW);

        assertThat(legacy).doesNotExist();
        assertThat(current).doesNotExist();
    }

    private static BuildTempSweeper sweeperWithTimeout(Path root, Duration timeout) {
        return new BuildTempSweeper(
                new ImageBuildProperties(DataSize.ofMegabytes(200), timeout, root.toString()),
                Clock.fixed(NOW, Clock.systemUTC().getZone()));
    }

    @Test
    void thresholdIsTwiceTheBuildTimeoutWhenThatExceedsSixHours(@TempDir Path root) throws IOException {
        Path running = dirWithAge(root, "shipir-build-running", Duration.ofHours(7));
        Path abandoned = dirWithAge(root, "shipir-build-abandoned", Duration.ofHours(9));

        sweeperWithTimeout(root, Duration.ofHours(4)).sweepDirectory(root, NOW);

        assertThat(running).isDirectory();
        assertThat(abandoned).doesNotExist();
    }

    @Test
    void thresholdNeverDropsBelowSixHoursForShortTimeouts(@TempDir Path root) throws IOException {
        Path young = dirWithAge(root, "shipir-build-young", Duration.ofHours(5));

        sweeperWithTimeout(root, Duration.ofMinutes(1)).sweepDirectory(root, NOW);

        assertThat(young).isDirectory();
    }

    @Test
    void symlinkInsideStaleDirIsRemovedButItsTargetSurvives(@TempDir Path root, @TempDir Path outside) throws IOException {
        Path target = Files.writeString(outside.resolve("precious.txt"), "keep");
        Path stale = dirWithAge(root, "shipir-build-stale", Duration.ofDays(2));
        Files.createSymbolicLink(stale.resolve("dir-link"), outside);
        Files.createSymbolicLink(stale.resolve("file-link"), target);
        Files.setLastModifiedTime(stale, FileTime.from(NOW.minus(Duration.ofDays(2))));

        sweeper(root).sweepDirectory(root, NOW);

        assertThat(stale).doesNotExist();
        assertThat(target).hasContent("keep");
        assertThat(outside).isDirectory();
    }

    @Test
    void symlinkedShipirBuildNameInSystemTempIsNeitherFollowedNorDeleted(@TempDir Path systemTemp, @TempDir Path outside)
            throws IOException {
        Path target = Files.writeString(outside.resolve("precious.txt"), "keep");
        Files.setLastModifiedTime(outside, FileTime.from(NOW.minus(Duration.ofDays(3))));
        Path link = Files.createSymbolicLink(systemTemp.resolve("shipir-build-link"), outside);
        BuildTempSweeper unconfigured = new BuildTempSweeper(
                new ImageBuildProperties(DataSize.ofMegabytes(200), Duration.ofMinutes(30), ""),
                Clock.fixed(NOW, Clock.systemUTC().getZone()));
        String previous = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", systemTemp.toString());
        try {
            unconfigured.sweep();
        } finally {
            System.setProperty("java.io.tmpdir", previous);
        }

        assertThat(target).hasContent("keep");
        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isTrue();
    }
}
