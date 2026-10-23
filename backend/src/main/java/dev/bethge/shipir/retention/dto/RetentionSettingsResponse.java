package dev.bethge.shipir.retention.dto;

import java.time.Instant;

public record RetentionSettingsResponse(int auditLogDays, int notificationDays, int chatSessionDays, Instant updatedAt) {
}
