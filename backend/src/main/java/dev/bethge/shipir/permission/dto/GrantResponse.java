package dev.bethge.shipir.permission.dto;

import dev.bethge.shipir.permission.PermissionLevel;

import java.util.UUID;

/** Host grant: {@code hostId} set. Deployment grant: {@code deploymentId} set, {@code deploymentHostId} is its host. */
public record GrantResponse(UUID hostId, UUID deploymentId, UUID deploymentHostId, PermissionLevel level) {
}
