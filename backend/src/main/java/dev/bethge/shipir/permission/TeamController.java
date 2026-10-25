package dev.bethge.shipir.permission;

import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.permission.dto.PermissionsResponse;
import dev.bethge.shipir.permission.dto.TeamDetailResponse;
import dev.bethge.shipir.permission.dto.TeamRequest;
import dev.bethge.shipir.permission.dto.TeamResponse;
import dev.bethge.shipir.permission.dto.UpdatePermissionsRequest;
import dev.bethge.shipir.permission.dto.UpdateTeamMembersRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** ADMIN-only via the {@code /api/teams/**} matcher in SecurityConfig. */
@RestController
class TeamController {

    private final TeamService teams;
    private final PermissionGrantService grants;

    TeamController(TeamService teams, PermissionGrantService grants) {
        this.teams = teams;
        this.grants = grants;
    }

    @GetMapping("/api/teams")
    List<TeamResponse> list() {
        return teams.list();
    }

    @PostMapping("/api/teams")
    @ResponseStatus(HttpStatus.CREATED)
    TeamResponse create(@Valid @RequestBody TeamRequest body) {
        return teams.create(body.name());
    }

    @GetMapping("/api/teams/{id}")
    TeamDetailResponse get(@PathVariable UUID id) {
        return teams.get(id);
    }

    @PutMapping("/api/teams/{id}")
    TeamResponse rename(@PathVariable UUID id, @Valid @RequestBody TeamRequest body) {
        return teams.rename(id, body.name());
    }

    @DeleteMapping("/api/teams/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        teams.delete(id);
    }

    @PutMapping("/api/teams/{id}/members")
    TeamDetailResponse replaceMembers(@PathVariable UUID id, @Valid @RequestBody UpdateTeamMembersRequest body) {
        return teams.replaceMembers(id, body.userIds());
    }

    @GetMapping("/api/teams/{id}/permissions")
    PermissionsResponse permissions(@PathVariable UUID id) {
        return grants.getForTeam(id);
    }

    @PutMapping("/api/teams/{id}/permissions")
    PermissionsResponse putPermissions(@PathVariable UUID id, @Valid @RequestBody UpdatePermissionsRequest body) {
        return grants.replaceForTeam(id, body.grants());
    }

    @ExceptionHandler(PermissionException.class)
    ResponseEntity<ErrorResponse> handle(PermissionException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
