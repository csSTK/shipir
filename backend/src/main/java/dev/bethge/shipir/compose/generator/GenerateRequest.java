package dev.bethge.shipir.compose.generator;

import dev.bethge.shipir.compose.generator.model.ComposeSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** {@code hostId} (optional) is the target environment; it lets the generator refuse labels its managed proxy ignores. */
public record GenerateRequest(@NotNull @Valid ComposeSpec spec, UUID hostId) {
}
