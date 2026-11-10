package dev.bethge.shipir.auth.oidc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The provider identity an ADMIN pre-links to a user: the OIDC issuer and the provider's {@code sub} claim. */
public record LinkExternalIdentityRequest(@NotBlank @Size(max = 2048) String issuer,
                                          @NotBlank @Size(max = 255) String subject) {
}
