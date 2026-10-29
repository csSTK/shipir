package dev.bethge.shipir.docker.dto;

import java.time.Instant;

/**
 * One line of container output. {@code timestamp} is only set when timestamps were requested; a continuation chunk
 * of a split over-long line has a null timestamp even then.
 * TTY containers do not separate the streams, their output is reported as STDOUT.
 */
public record LogLine(Stream stream, String text, Instant timestamp) {

    public enum Stream { STDOUT, STDERR }
}
