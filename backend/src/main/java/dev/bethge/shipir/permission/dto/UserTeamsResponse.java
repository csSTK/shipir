package dev.bethge.shipir.permission.dto;

import java.util.List;

/** Teams of one user (inherited access), sorted by team name. */
public record UserTeamsResponse(List<UserTeamResponse> teams) {
}
