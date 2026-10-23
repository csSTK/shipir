package dev.bethge.shipir.notification.dto;

/** {@code status}: SUCCESS, HTTP_&lt;code&gt;, TIMEOUT or FAILED. */
public record WebhookTestResponse(boolean ok, String status) {
}
