package dev.bethge.shipir.compose.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Git source of a stack; {@code credentialId} is null for public repositories. Never contains a token.
 * {@code lastPollError} is the error code of the last failed auto-update check (null when fine).
 */
public record GitInfo(String repoUrl, String ref, String composePath, UUID credentialId, String lastCommitSha,
                      boolean autoUpdateEnabled, Integer autoUpdateIntervalSeconds, Instant lastPolledAt,
                      String lastPollError) {
}
