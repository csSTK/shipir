package dev.bethge.shipir.permission.dto;

import dev.bethge.shipir.permission.PermissionLevel;

import java.util.UUID;

/** Grant of a team with the target's name. Host grant: {@code hostId}/{@code hostName}; deployment grant: {@code deploymentId}/{@code deploymentName}. */
public record UserTeamGrantResponse(UUID hostId, String hostName, UUID deploymentId, String deploymentName,
                                    PermissionLevel level) {
}
