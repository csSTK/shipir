package dev.bethge.shipir.permission.dto;

import java.util.List;
import java.util.UUID;

/** A team the user belongs to with the team's grants. No member list. */
public record UserTeamResponse(UUID id, String name, List<UserTeamGrantResponse> grants) {
}
