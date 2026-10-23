package dev.bethge.shipir.auth.dto;

import dev.bethge.shipir.auth.Role;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateUserRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9._-]{3,50}", message = "must be 3-50 characters: letters, digits, '.', '_' or '-'")
        String username,
        @NotNull Role role) {
}
