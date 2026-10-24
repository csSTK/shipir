package dev.bethge.shipir.auth.totp.dto;

/** Login answer when the password was right but a second factor is still needed. */
public record MfaRequiredResponse(boolean mfaRequired) {
}
