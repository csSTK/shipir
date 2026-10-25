package dev.bethge.shipir.streaming;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Frame of {@code /topic/hosts/{hostId}/deployments/{project}/logs}. LINE carries the origin ({@code service},
 * {@code container} name, {@code containerId}), {@code stream} (STDOUT/STDERR) and {@code line}. STATUS about one
 * container (ENDED, or ERROR with a stable code in {@code line}) carries its origin too; a STATUS ERROR without
 * origin is about the whole project (e.g. CONTAINER_LIMIT, NOT_FOUND) and does not end the stream.
 * {@code timestamp} (ISO-8601 instant) is only set on LINE frames of a stream subscribed with {@code timestamps=true}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeploymentLogMessage(long seq, Type type, String service, String container, String containerId,
                                   String stream, String line, Status status, String timestamp) {

    public enum Type { LINE, STATUS }

    public enum Status { ENDED, ERROR }
}
