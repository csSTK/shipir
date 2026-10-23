package dev.bethge.shipir.notification.dto;

import dev.bethge.shipir.notification.NotificationType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Never carries the full URL, only {@code urlHint} (scheme, host, port). */
public record WebhookResponse(UUID id, String name, String urlHint, List<NotificationType> eventTypes,
                              boolean enabled, Instant createdAt, Instant lastDeliveryAt,
                              String lastDeliveryStatus) {
}
