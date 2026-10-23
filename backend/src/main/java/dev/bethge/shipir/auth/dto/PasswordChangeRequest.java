package dev.bethge.shipir.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The size cap only bounds hashing cost; the policy itself (15 to 128) is enforced by the service. */
public record PasswordChangeRequest(
        @NotBlank @Size(max = 1024) String currentPassword,
        @NotBlank @Size(max = 1024) String newPassword) {

    /** Never print the passwords. */
    @Override
    public String toString() {
        return "PasswordChangeRequest[]";
    }
}
