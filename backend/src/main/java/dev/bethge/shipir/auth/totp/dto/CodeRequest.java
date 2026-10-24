package dev.bethge.shipir.auth.totp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A 6-digit authenticator code or (verify/disable only) a recovery code. */
public record CodeRequest(@NotBlank @Size(max = 64) String code) {

    @Override
    public String toString() {
        return "CodeRequest[***]";
    }
}
