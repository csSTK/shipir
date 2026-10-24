package dev.bethge.shipir.compose;

import java.time.Instant;
import java.util.UUID;

/** Published when the auto-update check of a Git deployment fails with an error code different from the stored one. */
public record GitAutoUpdateFailedEvent(UUID hostId, String hostName, UUID deploymentId, String deploymentName,
                                       String errorCode, Instant occurredAt) {
}
