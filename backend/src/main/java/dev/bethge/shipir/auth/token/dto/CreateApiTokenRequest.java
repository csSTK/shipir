package dev.bethge.shipir.auth.token.dto;

import dev.bethge.shipir.auth.stepup.StepUpCredentials;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * {@code expiresAt} is optional; without it the token expires after the configured maximum lifetime.
 * {@code stepUp}: the re-authentication credentials (see {@code StepUpService}); SSO users send none.
 */
public record CreateApiTokenRequest(@NotBlank @Size(max = 100) String name, Instant expiresAt,
                                    @Valid StepUpCredentials stepUp) {
}
