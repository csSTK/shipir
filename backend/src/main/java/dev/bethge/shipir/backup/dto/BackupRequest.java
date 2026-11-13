package dev.bethge.shipir.backup.dto;

import dev.bethge.shipir.auth.stepup.StepUpCredentials;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Passphrase for the archive encryption plus the step-up credentials (current password and, with two-factor, a
 * code; SSO users send none). {@link #toString()} never shows either.
 */
public record BackupRequest(
        @NotNull @Size(min = 12, max = 128) String passphrase,
        @Valid StepUpCredentials stepUp) {

    @Override
    public String toString() {
        return "BackupRequest[passphrase=***]";
    }
}
