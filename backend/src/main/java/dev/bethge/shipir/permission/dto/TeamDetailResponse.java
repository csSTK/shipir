package dev.bethge.shipir.permission.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TeamDetailResponse(UUID id, String name, List<TeamMemberResponse> members, Instant createdAt) {
}
