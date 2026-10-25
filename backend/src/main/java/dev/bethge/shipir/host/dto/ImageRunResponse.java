package dev.bethge.shipir.host.dto;

/** Accepted image build or push; output is streamed on {@code /topic/deployments/{runId}}. */
public record ImageRunResponse(String runId) {
}
