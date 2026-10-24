package dev.bethge.shipir.config_editor.dto;

import jakarta.validation.constraints.NotNull;

public record ConfigFileWriteRequest(@NotNull String content) {
}
