package dev.bethge.shipir.compose.generator;

import dev.bethge.shipir.compose.generator.model.ComposeSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record GenerateRequest(@NotNull @Valid ComposeSpec spec) {
}
