package dev.bethge.shipir.proxy.config.dto;

import jakarta.validation.constraints.NotNull;

/** New content of the custom file; empty is allowed (it clears the file). */
public record CustomConfigRequest(@NotNull String content) {
}
