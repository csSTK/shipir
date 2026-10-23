package dev.bethge.shipir.auth;

/** Same exception for unknown user, wrong, expired, used or exhausted code, so callers learn nothing. */
final class InvalidResetCodeException extends RuntimeException {

    InvalidResetCodeException() {
        super("Invalid or expired reset code", null, false, false);
    }
}
