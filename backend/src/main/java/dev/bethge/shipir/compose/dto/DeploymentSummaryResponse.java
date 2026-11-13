package dev.bethge.shipir.compose.dto;

import dev.bethge.shipir.compose.DeploymentSource;
import dev.bethge.shipir.compose.DeploymentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code systemManaged}: the managed proxy deployment, operable by ADMIN only (the UI shows it as managed via Domains).
 * {@code canOperate}: the caller has OPERATE on this host or deployment; false for a VIEW-only grant.
 */
public record DeploymentSummaryResponse(UUID id, String name, DeploymentStatus status, DeploymentSource source,
                                   Instant lastDeployedAt, Instant createdAt,
                                   boolean systemManaged, boolean canOperate) {
}
