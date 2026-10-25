package dev.bethge.shipir.compose.dto;

import java.time.Instant;

/** Webhook state of a deployment; never contains the token (only its hash is stored). */
public record WebhookStatusResponse(boolean enabled, Instant createdAt, Instant rotatedAt) {
}
