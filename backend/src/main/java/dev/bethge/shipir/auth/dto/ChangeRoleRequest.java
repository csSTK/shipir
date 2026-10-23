package dev.bethge.shipir.auth.dto;

import dev.bethge.shipir.auth.Role;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(@NotNull Role role) {
}
