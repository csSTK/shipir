package dev.bethge.shipir.compose.dto;

import dev.bethge.shipir.compose.DeploymentSource;
import dev.bethge.shipir.compose.DeploymentStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Deployment with its current YAML; env values are never included, only the variable names. {@code git} is null for FILE deployments, {@code imageAutoUpdate} for Git deployments. */
public record DeploymentDetailResponse(UUID id, String name, DeploymentStatus status, DeploymentSource source,
                                  Instant lastDeployedAt, Instant createdAt, String yaml, List<String> envKeys,
                                  GitInfo git, ImageAutoUpdateInfo imageAutoUpdate) {
}
