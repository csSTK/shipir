package dev.bethge.shipir.streaming;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Frame of {@code /topic/hosts/{hostId}/containers/{containerId}/logs}. LINE carries {@code stream}
 * (STDOUT/STDERR) and {@code line}; STATUS carries {@code status} (ENDED, ERROR) and, for ERROR, a stable
 * {@code line} code such as NOT_FOUND. {@code timestamp} (ISO-8601 instant) is only set on LINE frames of
 * a stream subscribed with {@code timestamps=true}. A STATUS frame is the last one of a followed stream.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LogStreamMessage(long seq, Type type, String stream, String line, Status status,
                             String timestamp) {

    public enum Type { LINE, STATUS }

    public enum Status { ENDED, ERROR }
}
