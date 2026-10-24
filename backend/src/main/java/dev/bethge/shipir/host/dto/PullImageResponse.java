package dev.bethge.shipir.host.dto;

/** Accepted pull; progress is streamed on {@code /topic/deployments/{runId}}. */
public record PullImageResponse(String runId) {
}
