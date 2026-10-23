package dev.bethge.shipir.git.dto;

import java.time.Instant;
import java.util.UUID;

/** Never carries the token, only {@code tokenHint}. */
public record GitCredentialResponse(UUID id, String name, String username, String tokenHint, Instant createdAt,
                                    Instant updatedAt) {
}
