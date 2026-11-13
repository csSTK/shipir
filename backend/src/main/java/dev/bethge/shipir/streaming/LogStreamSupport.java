package dev.bethge.shipir.streaming;

import dev.bethge.shipir.docker.dto.LogLine;

import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;

/**
 * Logic shared by {@link LogStreamHandler} (one container) and {@link DeploymentLogStreamService} (one Compose
 * project): SUBSCRIBE header parsing, line capping and the per-second rate window. The reference counting and locking
 * stay in the two services, because their stream shapes differ (one follow vs. a worker with many followers).
 */
final class LogStreamSupport {

    private LogStreamSupport() {
    }

    /** {@code tail} header: 100, 500, 1000 or all (-1); null = {@code defaultTail}. Anything else is refused. */
    static int parseTail(String header, int defaultTail) {
        if (header == null) {
            return defaultTail;
        }
        return switch (header) {
            case "100" -> 100;
            case "500" -> 500;
            case "1000" -> 1000;
            case "all" -> -1;
            default -> throw new IllegalArgumentException("Invalid tail");
        };
    }

    /** {@code timestamps} header: true or false; null = false. Anything else is refused. */
    static boolean parseTimestamps(String header) {
        if (header == null) {
            return false;
        }
        return switch (header) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Invalid timestamps");
        };
    }

    /** Cuts a line to {@code maxLength} characters and marks the cut. */
    static String capLength(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength) + " [truncated]" : text;
    }

    /** ISO instant of the line, only when the subscriber asked for timestamps. */
    static String timestampOf(LogLine line, boolean wanted) {
        return line.timestamp() == null || !wanted ? null : line.timestamp().toString();
    }

    static String skippedMarker(int dropped) {
        return "[" + dropped + " lines skipped: rate limit]";
    }

    static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            // closing is best effort, the stream is gone either way
        }
    }

    /**
     * Lower bound for re-following an ended stream. Docker compares {@code since} with the daemon clock, which may
     * differ from Shipir's on a remote host: prefer the daemon's own timestamp of the last delivered line
     * ({@code lastDaemonTimestamp}, null when timestamps were off), else Shipir's end time backed off by {@code margin}
     * (covers a daemon clock behind by up to the margin; a clock ahead only produces repeated lines).
     */
    static Instant followSince(Instant lastDaemonTimestamp, Instant endedAt, Duration margin) {
        return lastDaemonTimestamp != null ? lastDaemonTimestamp : endedAt.minus(margin);
    }

    /** Lines per wall-clock second with a counter of what was dropped. Not thread safe; callers hold the stream lock. */
    static final class RateWindow {
        private long second = Long.MIN_VALUE;
        private int lines;
        private int dropped;

        /** Moves to {@code nowSecond}; returns how many lines the previous second dropped (0 if the second is unchanged). */
        int roll(long nowSecond) {
            if (nowSecond == second) {
                return 0;
            }
            second = nowSecond;
            lines = 0;
            int d = dropped;
            dropped = 0;
            return d;
        }

        boolean isFull(int maxLines) {
            return lines >= maxLines;
        }

        void take() {
            lines++;
        }

        void drop() {
            dropped++;
        }
    }
}
