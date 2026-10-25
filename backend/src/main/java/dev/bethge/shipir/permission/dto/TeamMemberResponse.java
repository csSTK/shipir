package dev.bethge.shipir.permission.dto;

import java.util.UUID;

public record TeamMemberResponse(UUID userId, String username) {
}
