package dev.bethge.shipir.permission;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.UserAccessChangedEvent;
import dev.bethge.shipir.permission.dto.TeamDetailResponse;
import dev.bethge.shipir.permission.dto.TeamMemberResponse;
import dev.bethge.shipir.permission.dto.TeamResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Teams and their members. Grants of a team are managed by {@link PermissionGrantService}; the decision logic in
 * {@link PermissionService} reads memberships live, so a removed member loses the team's access immediately.
 */
@Service
public class TeamService {

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final PermissionRepository grants;
    private final UserRepository users;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    TeamService(TeamRepository teams, TeamMemberRepository members, PermissionRepository grants,
                UserRepository users, Clock clock, ApplicationEventPublisher events) {
        this.events = events;
        this.teams = teams;
        this.members = members;
        this.grants = grants;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<TeamResponse> list() {
        return teams.findAllByOrderByNameAsc().stream()
                .map(t -> toResponse(t, members.countByTeam(t.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamDetailResponse get(UUID id) {
        return detail(find(id));
    }

    @Auditable(action = "TEAM_CREATE", targetType = "TEAM", targetId = "#result?.id()",
            details = "{'name': #result.name()}")
    @Transactional
    TeamResponse create(String name) {
        String trimmed = name.trim();
        requireFreeName(trimmed, null);
        try {
            return toResponse(teams.saveAndFlush(new Team(trimmed, clock.instant())), 0);
        } catch (DataIntegrityViolationException e) {
            throw nameTaken();
        }
    }

    @Auditable(action = "TEAM_UPDATE", targetType = "TEAM", targetId = "#id", details = "{'name': #result.name()}")
    @Transactional
    TeamResponse rename(UUID id, String name) {
        Team team = find(id);
        String trimmed = name.trim();
        requireFreeName(trimmed, id);
        team.rename(trimmed);
        try {
            teams.flush();
        } catch (DataIntegrityViolationException e) {
            throw nameTaken();
        }
        return toResponse(team, members.countByTeam(id));
    }

    @Auditable(action = "TEAM_DELETE", targetType = "TEAM", targetId = "#id")
    @Transactional
    void delete(UUID id) {
        find(id);
        Set<UUID> memberIds = members.findByTeam(id).stream().map(TeamMember::getUserId).collect(Collectors.toSet());
        // memberships and team grants go with it (ON DELETE CASCADE)
        teams.deleteById(id);
        teams.flush();
        publishAccessChanged(memberIds);
    }

    /** Replaces the complete member set of the team. */
    @Auditable(action = "TEAM_MEMBERS_UPDATE", targetType = "TEAM", targetId = "#id",
            details = "{'memberCount': #result.members().size()}")
    @Transactional
    TeamDetailResponse replaceMembers(UUID id, List<UUID> userIds) {
        Team team = find(id);
        Set<UUID> wanted = new HashSet<>(userIds == null ? List.of() : userIds);
        if (wanted.contains(null) || users.findAllById(wanted).size() != wanted.size()) {
            throw new PermissionException(HttpStatus.BAD_REQUEST, "UNKNOWN_USER", "Unknown user");
        }
        Set<UUID> current = members.findByTeam(id).stream().map(TeamMember::getUserId).collect(Collectors.toSet());
        applyDiff(id, current, wanted);
        Set<UUID> changed = new HashSet<>(current);
        changed.addAll(wanted);
        changed.removeIf(u -> current.contains(u) && wanted.contains(u));
        publishAccessChanged(changed);
        return detail(team);
    }

    /**
     * Hook for an identity provider (OIDC): makes {@code userId} a member of exactly the teams whose name equals
     * one of {@code groupNames} (exact match), and of no other team. Unknown group names are ignored, teams are
     * never created implicitly. Runs as the caller's transaction or its own; does not depend on any OIDC code.
     */
    @Transactional
    public void syncMemberships(UUID userId, Collection<String> groupNames) {
        Set<UUID> wanted = groupNames == null || groupNames.isEmpty() ? Set.of()
                : teams.findByNameIn(groupNames).stream().map(Team::getId).collect(Collectors.toSet());
        Set<UUID> current = members.findByUser(userId).stream().map(TeamMember::getTeamId).collect(Collectors.toSet());
        for (UUID teamId : current) {
            if (!wanted.contains(teamId)) {
                members.deleteById(new TeamMemberId(teamId, userId));
            }
        }
        for (UUID teamId : wanted) {
            if (!current.contains(teamId)) {
                members.save(new TeamMember(teamId, userId, clock.instant()));
            }
        }
        members.flush();
        users.findById(userId).ifPresent(u -> {
            if (!wanted.equals(current)) {
                events.publishEvent(new UserAccessChangedEvent(u.getUsername()));
            }
        });
    }

    /** Open consoles and subscriptions of these users are re-checked: their team access may have changed. */
    private void publishAccessChanged(Set<UUID> userIds) {
        users.findAllById(userIds).forEach(u -> events.publishEvent(new UserAccessChangedEvent(u.getUsername())));
    }

    private void applyDiff(UUID teamId, Set<UUID> current, Set<UUID> wanted) {
        for (UUID userId : current) {
            if (!wanted.contains(userId)) {
                members.deleteById(new TeamMemberId(teamId, userId));
            }
        }
        for (UUID userId : wanted) {
            if (!current.contains(userId)) {
                members.save(new TeamMember(teamId, userId, clock.instant()));
            }
        }
        members.flush();
    }

    private Team find(UUID id) {
        return teams.findById(id).orElseThrow(
                () -> new PermissionException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "Team not found"));
    }

    private void requireFreeName(String name, UUID selfId) {
        boolean taken = selfId == null ? teams.existsByNameIgnoreCase(name)
                : teams.existsByNameIgnoreCaseAndIdNot(name, selfId);
        if (taken) {
            throw nameTaken();
        }
    }

    private PermissionException nameTaken() {
        return new PermissionException(HttpStatus.CONFLICT, "TEAM_NAME_TAKEN", "A team with this name already exists");
    }

    private TeamResponse toResponse(Team t, long memberCount) {
        return new TeamResponse(t.getId(), t.getName(), memberCount, t.getCreatedAt());
    }

    private TeamDetailResponse detail(Team team) {
        Set<UUID> ids = members.findByTeam(team.getId()).stream().map(TeamMember::getUserId).collect(Collectors.toSet());
        Map<UUID, User> byId = users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<TeamMemberResponse> list = ids.stream().filter(byId::containsKey)
                .map(id -> new TeamMemberResponse(id, byId.get(id).getUsername()))
                .sorted(Comparator.comparing(TeamMemberResponse::username, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new TeamDetailResponse(team.getId(), team.getName(), list, team.getCreatedAt());
    }
}
