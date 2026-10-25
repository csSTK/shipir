package dev.bethge.shipir.auth.token.dto;

import java.time.Instant;
import java.util.UUID;

/** A token as listed: metadata and the non-secret prefix, never the token. */
public record ApiTokenResponse(UUID id, String name, String prefix, Instant createdAt, Instant expiresAt,
                               Instant lastUsedAt) {
}
