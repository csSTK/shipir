package dev.bethge.shipir.notification.dto;

/** {@code status}: SUCCESS or FAILED, nothing finer (the test must not reveal what an arbitrary target answered). */
public record WebhookTestResponse(boolean ok, String status) {
}
