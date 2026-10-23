package dev.bethge.shipir.notification.dto;

import dev.bethge.shipir.notification.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(UUID id, NotificationType type, UUID hostId, String hostName, String message,
                                   boolean read, Instant createdAt) {
}
