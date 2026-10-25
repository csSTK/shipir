package dev.bethge.shipir.compose;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the scheduled image auto-update of a deployment ended: {@code applied} true when the new images run
 * healthy, false when they did not (rolled back or not). {@code errorCode} is null on success.
 */
public record ImageAutoUpdateEvent(UUID hostId, String hostName, UUID deploymentId, String deploymentName,
                                   boolean applied, String errorCode, Instant occurredAt) {
}
