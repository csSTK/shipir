package dev.bethge.shipir.retention.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** {@code chatSessionDays} is optional: when omitted the stored value is kept. */
public record RetentionSettingsRequest(
        @NotNull @Min(1) @Max(3650) Integer auditLogDays,
        @NotNull @Min(1) @Max(3650) Integer notificationDays,
        @Min(1) @Max(3650) Integer chatSessionDays) {
}
