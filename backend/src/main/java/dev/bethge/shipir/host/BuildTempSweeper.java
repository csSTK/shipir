package dev.bethge.shipir.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Removes spool directories that {@link ImageBuildService} left behind after a crash or kill: the worker deletes its
 * directory in a {@code finally}, which a JVM kill does not run.
 *
 * <p>Runs once at startup and then hourly. Only direct children that are real directories (symlinks are never followed
 * nor deleted) and whose last modification is older than {@link #STALE_AFTER} are removed. The threshold is well above
 * the build timeout, so a build that is still running is never touched.
 *
 * <p>Which directories are swept depends on where the spool lives:
 * <ul>
 *   <li>{@code shipir.images.build-temp-dir} empty (the default): the system temp directory is shared, so only
 *       {@code shipir-build-*} is swept there. Bare {@code build-*} names belong to other programs and are kept.</li>
 *   <li>{@code shipir.images.build-temp-dir} set: the dir is Shipir-owned, so {@code shipir-build-*} and the legacy
 *       {@code build-*} names from before the prefix existed are swept.</li>
 * </ul>
 */
@Component
class BuildTempSweeper {

    private static final Logger log = LoggerFactory.getLogger(BuildTempSweeper.class);

    static final Duration STALE_AFTER = Duration.ofHours(6);
    /** Spool name used before {@link ImageBuildService#SPOOL_PREFIX}; only swept in an explicitly configured dir. */
    private static final String LEGACY_PREFIX = "build-";

    private final ImageBuildProperties properties;
    private final Clock clock;

    BuildTempSweeper(ImageBuildProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H")
    void sweep() {
        Instant now = clock.instant();
        String configured = properties.buildTempDir();
        if (configured == null || configured.isBlank()) {
            sweepDirectory(Path.of(System.getProperty("java.io.tmpdir")), List.of(ImageBuildService.SPOOL_PREFIX), now);
            return;
        }
        sweepDirectory(Path.of(configured), List.of(ImageBuildService.SPOOL_PREFIX, LEGACY_PREFIX), now);
    }

    /** Package-private for tests: sweeps a Shipir-owned {@code root} (both prefixes) as if the time were {@code now}. */
    void sweepDirectory(Path root, Instant now) {
        sweepDirectory(root, List.of(ImageBuildService.SPOOL_PREFIX, LEGACY_PREFIX), now);
    }

    private static void sweepDirectory(Path root, List<String> prefixes, Instant now) {
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = now.minus(STALE_AFTER);
        int removed = 0;
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (isStaleSpoolDirectory(child, prefixes, cutoff)) {
                    deleteTree(child);
                    removed++;
                }
            }
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not sweep temporary build directory: {}", e.getClass().getSimpleName());
        }
        if (removed > 0) {
            log.info("Removed {} stale image build directories", removed);
        }
    }

    private static boolean isStaleSpoolDirectory(Path child, List<String> prefixes, Instant cutoff) {
        Path name = child.getFileName();
        if (name == null || prefixes.stream().noneMatch(name.toString()::startsWith)
                || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    /** Walks without following links, so a symlink inside a stale directory is removed as a link, its target kept. */
    private static void deleteTree(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete stale build file: {}", e.getClass().getSimpleName());
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not delete stale build directory: {}", e.getClass().getSimpleName());
        }
    }
}
