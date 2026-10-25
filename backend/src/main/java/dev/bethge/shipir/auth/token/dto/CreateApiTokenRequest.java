package dev.bethge.shipir.auth.token.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** {@code expiresAt} is optional; without it the token expires after the configured maximum lifetime. */
public record CreateApiTokenRequest(@NotBlank @Size(max = 100) String name, Instant expiresAt) {
}
