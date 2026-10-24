package dev.bethge.shipir.config_editor.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfigFileRestoreRequest(@NotBlank String backupId) {
}
