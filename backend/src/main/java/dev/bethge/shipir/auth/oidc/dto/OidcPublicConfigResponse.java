package dev.bethge.shipir.auth.oidc.dto;

/** What the login page needs; {@code displayName} is the provider's host, null while disabled. */
public record OidcPublicConfigResponse(boolean enabled, String displayName, boolean localLoginEnabled) {
}
