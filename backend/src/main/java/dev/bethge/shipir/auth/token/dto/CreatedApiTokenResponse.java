package dev.bethge.shipir.auth.token.dto;

import java.time.Instant;
import java.util.UUID;

/** Response of the create call: the only time the plaintext {@code token} is ever returned. */
public record CreatedApiTokenResponse(UUID id, String name, String prefix, Instant createdAt, Instant expiresAt,
                                      String token) {

    @Override
    public String toString() {
        return "CreatedApiTokenResponse[id=" + id + ", prefix=" + prefix + "]";
    }
}
