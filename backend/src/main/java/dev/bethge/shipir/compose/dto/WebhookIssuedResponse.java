package dev.bethge.shipir.compose.dto;

import java.time.Instant;

/** Result of enabling or rotating a webhook: the only time {@code token} and {@code path} (containing it) are shown. */
public record WebhookIssuedResponse(boolean enabled, Instant createdAt, Instant rotatedAt, String token, String path) {

    @Override
    public String toString() {
        return "WebhookIssuedResponse[enabled=" + enabled + "]";
    }
}
