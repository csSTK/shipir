package dev.bethge.shipir.host.dto;

/** Image to pull, e.g. {@code nginx:1.27} or {@code ghcr.io/owner/app@sha256:...}; validated by the service. */
public record PullImageRequest(String reference) {
}
