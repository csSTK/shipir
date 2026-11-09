package dev.bethge.shipir.compose.dto;

import dev.bethge.shipir.compose.execution.ComposeOptions;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Compose profiles to activate for every run; an empty list clears them. Names follow the Compose profile syntax and use
 * the same pattern as {@code ComposeOptions.NAME_REGEX} (a leading {@code _} is allowed, a leading {@code -} is not).
 */
public record ProfilesRequest(
        @NotNull @Size(max = 50) List<@NotNull @Pattern(regexp = ComposeOptions.NAME_REGEX) String> profiles) {
}
