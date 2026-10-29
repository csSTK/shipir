package dev.bethge.shipir.compose.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Compose profiles to activate for every run; an empty list clears them. Names follow the Compose profile syntax, the
 * same rule as {@code ComposeOptions.NAME} (a leading {@code _} is allowed, a leading {@code -} is not). Keep them in sync.
 */
public record ProfilesRequest(
        @NotNull @Size(max = 50) List<@NotNull @Pattern(regexp = "[A-Za-z0-9_][A-Za-z0-9_.-]{0,62}") String> profiles) {
}
