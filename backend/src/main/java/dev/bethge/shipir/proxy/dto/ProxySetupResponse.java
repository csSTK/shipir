package dev.bethge.shipir.proxy.dto;

/** Result of a setup or update; {@code runId} is the deployment run streamed on {@code /topic/deployments/{runId}}, null when nothing was redeployed. */
public record ProxySetupResponse(ProxyInstanceView instance, String runId) {
}
