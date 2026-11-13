package dev.bethge.shipir.auth.stepup;

import jakarta.validation.constraints.Size;

/**
 * What a user sends to repeat their authentication for a sensitive action: the current password and, when two-factor
 * is enabled, a TOTP or recovery code. SSO users send nothing (they re-authenticate at the provider). Embed this as
 * an optional {@code stepUp} field in the request body of the guarded endpoint. The size caps only bound hashing cost.
 */
public record StepUpCredentials(@Size(max = 1024) String password, @Size(max = 64) String code) {

    /** Never print the credentials. */
    @Override
    public String toString() {
        return "StepUpCredentials[]";
    }
}
