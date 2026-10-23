package dev.bethge.shipir.compose.dto;

import java.util.UUID;

/** Result of starting a deployment run; output is streamed on {@code /topic/deployments/{runId}}. */
public record DeploymentRunResponse(String runId, UUID versionId) {
}
