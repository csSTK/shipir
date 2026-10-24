package dev.bethge.shipir.host;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@link ContainerHealthWatcher} when a container crashed (exited with a non-zero code) or turned
 * unhealthy. {@code deploymentId}/{@code deploymentName} are set when the container belongs to a Compose project that
 * Shipir manages; {@code exitCode} is only set for {@link Kind#CRASHED}.
 */
public record ContainerProblemEvent(Kind kind, UUID hostId, String hostName, String containerId, String containerName,
                                    UUID deploymentId, String deploymentName, Integer exitCode, Instant occurredAt) {

    public enum Kind { CRASHED, UNHEALTHY }
}
