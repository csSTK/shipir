package dev.bethge.shipir.git.dto;

import java.time.Instant;
import java.util.UUID;

/** {@code host} is null for a credential created before host binding existed. Never carries the token, only {@code tokenHint}. */
public record GitCredentialResponse(UUID id, String name, String host, String username, String tokenHint, Instant createdAt,
                                    Instant updatedAt) {
}
