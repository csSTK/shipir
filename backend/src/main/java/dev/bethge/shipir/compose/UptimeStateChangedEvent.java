package dev.bethge.shipir.compose;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an uptime check changed state worth a notification: {@code down} true after the configured number of
 * consecutive failures, false when a down check recovered. {@code errorCode} (fixed code) is null on recovery. Carries
 * no URL.
 */
public record UptimeStateChangedEvent(UUID hostId, String hostName, UUID deploymentId, String deploymentName,
                                      String checkName, boolean down, String errorCode, Instant occurredAt) {
}
