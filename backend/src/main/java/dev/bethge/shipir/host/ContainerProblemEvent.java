package dev.bethge.shipir.host;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@link ContainerHealthWatcher} when a container crashed (exited with a non-zero code) or turned
 * unhealthy. {@code deploymentId}/{@code deploymentName} are set when the container belongs to a Compose project that
 * Shipir manages; {@code exitCode} is only set for {@link Kind#CRASHED}. {@code restartCount}/{@code restartWindow} are only set when the crash is reported as a
 * restart loop (that many crashes within the window).
 */
public record ContainerProblemEvent(Kind kind, UUID hostId, String hostName, String containerId, String containerName,
                                    UUID deploymentId, String deploymentName, Integer exitCode, Instant occurredAt, Integer restartCount,
                                    Duration restartWindow) {

    /** A plain event, not part of a restart loop. */
    public ContainerProblemEvent(Kind kind, UUID hostId, String hostName, String containerId, String containerName,
                                 UUID deploymentId, String deploymentName, Integer exitCode, Instant occurredAt) {
        this(kind, hostId, hostName, containerId, containerName, deploymentId, deploymentName, exitCode, occurredAt,
                null, null);
    }

    public enum Kind { CRASHED, UNHEALTHY }
}
