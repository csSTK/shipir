package dev.bethge.shipir.permission.dto;

import dev.bethge.shipir.permission.PermissionLevel;

import java.util.UUID;

/** Exactly one of {@code hostId} / {@code deploymentId}. */
public record GrantRequest(UUID hostId, UUID deploymentId, PermissionLevel level) {
}
