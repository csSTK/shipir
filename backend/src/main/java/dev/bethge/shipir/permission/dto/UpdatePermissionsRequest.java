package dev.bethge.shipir.permission.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdatePermissionsRequest(@NotNull List<GrantRequest> grants) {
}
