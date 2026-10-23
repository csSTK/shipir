package dev.bethge.shipir.notification.dto;

import dev.bethge.shipir.notification.NotificationType;
import jakarta.validation.constraints.NotNull;

public record NotificationPreferenceDto(@NotNull NotificationType type, boolean inAppEnabled) {
}
