package dev.bethge.shipir.backup.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Passphrase for the archive encryption; {@link #toString()} never shows it. */
public record BackupRequest(
        @NotNull @Size(min = 12, max = 128) String passphrase) {

    @Override
    public String toString() {
        return "BackupRequest[passphrase=***]";
    }
}
