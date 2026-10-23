package dev.bethge.shipir.compose.dto;

import java.util.UUID;

/** Git source of a stack; {@code credentialId} is null for public repositories. Never contains a token. */
public record GitInfo(String repoUrl, String ref, String composePath, UUID credentialId, String lastCommitSha) {
}
