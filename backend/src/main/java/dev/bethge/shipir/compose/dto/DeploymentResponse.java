package dev.bethge.shipir.compose.dto;

import java.util.UUID;

/** Result of starting a deployment; output is streamed on {@code /topic/deployments/{deploymentId}}. */
public record DeploymentResponse(String deploymentId, UUID versionId) {
}
