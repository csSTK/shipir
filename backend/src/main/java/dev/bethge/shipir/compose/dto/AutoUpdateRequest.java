package dev.bethge.shipir.compose.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Auto-update settings of a Git deployment. {@code intervalSeconds} (minimum 60) is required when enabling unless an
 * interval was stored before; when omitted on disable the stored interval is kept.
 */
public record AutoUpdateRequest(@NotNull Boolean enabled, @Min(60) Integer intervalSeconds) {
}
