package dev.bethge.shipir.git;

import org.eclipse.jgit.lib.ProgressMonitor;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Cancels a running JGit fetch once an overall deadline has passed or the checkout directory grew beyond a byte cap.
 * JGit's own timeout only fires after a period without any data, so a slow-drip server could otherwise hold the
 * caller (and, for builds, the per-host slot) indefinitely. JGit polls {@link #isCancelled()} while it receives and
 * indexes the pack; the size cap counts everything in the checkout directory (pack plus working tree); the directory size is re-measured at most every {@link #SIZE_CHECK_NANOS}.
 */
final class CloneGuard implements ProgressMonitor {

    enum Violation { TIMEOUT, TOO_LARGE }

    private static final long SIZE_CHECK_NANOS = 100_000_000L;

    private final Path dir;
    private final long maxBytes;
    private final long deadlineNanos;
    private volatile long lastSizeCheck;
    private volatile Violation violation;

    CloneGuard(Path dir, long maxBytes, long timeoutNanos) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        this.deadlineNanos = System.nanoTime() + timeoutNanos;
        this.lastSizeCheck = System.nanoTime();
    }

    /** The reason the guard tripped, or null. */
    Violation violation() {
        return violation;
    }

    /** Remaining time until the deadline in nanoseconds (may be negative). */
    long remainingNanos() {
        return deadlineNanos - System.nanoTime();
    }

    /** Full check (deadline and size) regardless of throttling; returns the violation, if any. */
    Violation check() {
        if (violation == null) {
            if (remainingNanos() <= 0) {
                violation = Violation.TIMEOUT;
            } else if (dir != null && size(dir) > maxBytes) {
                violation = Violation.TOO_LARGE;
            }
        }
        return violation;
    }

    @Override
    public boolean isCancelled() {
        if (violation != null) {
            return true;
        }
        if (remainingNanos() <= 0) {
            violation = Violation.TIMEOUT;
        } else if (System.nanoTime() - lastSizeCheck >= SIZE_CHECK_NANOS) {
            lastSizeCheck = System.nanoTime();
            if (dir != null && size(dir) > maxBytes) {
                violation = Violation.TOO_LARGE;
            }
        }
        return violation != null;
    }

    static long size(Path dir) {
        long[] total = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // a vanishing or unreadable directory counts as what could be measured so far
        }
        return total[0];
    }

    /** Deletes {@code dir} without following symlinks; best effort. */
    static void deleteQuietly(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // best effort
        }
    }

    @Override public void start(int totalTasks) { }
    @Override public void beginTask(String title, int totalWork) { }
    @Override public void update(int completed) { }
    @Override public void endTask() { }

    @Override public void showDuration(boolean enabled) { }
}
