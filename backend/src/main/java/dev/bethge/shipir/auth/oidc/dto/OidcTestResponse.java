package dev.bethge.shipir.auth.oidc.dto;

/** {@code status}: OK, UNREACHABLE or INVALID (discovery document does not match the issuer). */
public record OidcTestResponse(String status) {
}
