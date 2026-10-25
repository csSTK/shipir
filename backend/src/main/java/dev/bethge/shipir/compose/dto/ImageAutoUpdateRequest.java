package dev.bethge.shipir.compose.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Image auto-update settings of a FILE deployment. {@code windowStart}/{@code windowEnd} are {@code HH:mm} in UTC and
 * must be given together (start inclusive, end exclusive, start after end wraps over midnight); both absent = any time.
 */
public record ImageAutoUpdateRequest(@NotNull Boolean enabled, String windowStart, String windowEnd) {
}
