package dev.bethge.shipir.compose.dto;

import java.time.Instant;

/**
 * Image auto-update settings and last automatic attempt of a FILE deployment. Window times are {@code HH:mm} UTC, null
 * without window; {@code lastResult} is APPLIED, FAILED or null (no attempt yet, or one still running).
 */
public record ImageAutoUpdateInfo(boolean enabled, String windowStart, String windowEnd, Instant lastAttemptAt,
                                  String lastResult) {
}
