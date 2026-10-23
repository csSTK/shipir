package dev.bethge.shipir.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The size caps only bound hashing cost; the password policy itself is enforced by the service. */
public record PasswordResetConfirmRequest(
        @NotBlank @Size(max = 255) String username,
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 1024) String newPassword) {

    /** Never print the code or the password. */
    @Override
    public String toString() {
        return "PasswordResetConfirmRequest[username=" + username + "]";
    }
}
