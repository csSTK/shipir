package dev.bethge.shipir.permission.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record UpdateTeamMembersRequest(@NotNull List<UUID> userIds) {
}
