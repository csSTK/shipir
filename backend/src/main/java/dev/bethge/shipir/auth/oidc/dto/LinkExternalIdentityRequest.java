package dev.bethge.shipir.auth.oidc.dto;

import dev.bethge.shipir.auth.stepup.StepUpCredentials;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The provider identity an ADMIN pre-links to a user: the OIDC issuer and the provider's {@code sub} claim.
 * {@code stepUp}: required only when linking yourself (password, and the code if two-factor is on). */
public record LinkExternalIdentityRequest(@NotBlank @Size(max = 2048) String issuer,
                                          @NotBlank @Size(max = 255) String subject,
                                          @Valid StepUpCredentials stepUp) {
}
