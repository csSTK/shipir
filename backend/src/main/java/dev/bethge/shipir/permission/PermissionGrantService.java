package dev.bethge.shipir.permission;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.UserAccessChangedEvent;
import dev.bethge.shipir.host.DockerHostRepository;
import org.springframework.context.ApplicationEventPublisher;
import dev.bethge.shipir.permission.dto.GrantRequest;
import dev.bethge.shipir.permission.dto.GrantResponse;
import dev.bethge.shipir.permission.dto.PermissionsResponse;
import dev.bethge.shipir.permission.dto.SubjectGrantResponse;
import dev.bethge.shipir.permission.dto.SubjectGrantsResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADMIN management of the grants of a user or a team. PUT replaces the complete set. The read methods are public
 * for the AI tool {@code list_access_grants}; callers must be ADMIN (the tool is registered for ADMIN only).
 */
@Service
public class PermissionGrantService {

    private final PermissionRepository grants;
    private final UserRepository users;
    private final DockerHostRepository hosts;
    private final DeploymentDirectory deployments;
    private final TeamRepository teams;
    private final TeamMemberRepository teamMembers;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    PermissionGrantService(PermissionRepository grants, UserRepository users, DockerHostRepository hosts,
                           DeploymentDirectory deployments, TeamRepository teams, TeamMemberRepository teamMembers, Clock clock,
                           ApplicationEventPublisher events) {
        this.grants = grants;
        this.users = users;
        this.hosts = hosts;
        this.deployments = deployments;
        this.teams = teams;
        this.teamMembers = teamMembers;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PermissionsResponse get(UUID userId) {
        find(userId);
        return response(userId);
    }

    @Auditable(action = "PERMISSION_UPDATE", targetType = "USER", targetId = "#userId")
    @Transactional
    PermissionsResponse replace(UUID userId, List<GrantRequest> requested) {
        User user = find(userId);
        List<GrantRequest> wanted = requested == null ? List.of() : requested;
        if (user.getRole() == Role.ADMIN && !wanted.isEmpty()) {
            throw new PermissionException(HttpStatus.BAD_REQUEST, "ADMIN_NEEDS_NO_GRANTS",
                    "Administrators can already access everything; grants are not allowed");
        }
        List<Permission> entities = build(userId, null, wanted);
        grants.deleteByUserId(userId);
        grants.flush();
        grants.saveAll(entities);
        events.publishEvent(new UserAccessChangedEvent(user.getUsername()));
        return response(userId);
    }

    /** Validates the requested grants and maps them to entities of exactly one subject (user xor team). */
    private List<Permission> build(UUID userId, UUID teamId, List<GrantRequest> wanted) {
        Set<UUID> seenHosts = new HashSet<>();
        Set<UUID> seenDeployments = new HashSet<>();
        List<Permission> entities = new ArrayList<>();
        for (GrantRequest g : wanted) {
            if (g == null || g.level() == null || (g.hostId() == null) == (g.deploymentId() == null)) {
                throw new PermissionException(HttpStatus.BAD_REQUEST, "INVALID_GRANT",
                        "Each grant needs a level and exactly one of hostId or deploymentId");
            }
            if (g.hostId() != null) {
                if (!hosts.existsById(g.hostId())) {
                    throw new PermissionException(HttpStatus.BAD_REQUEST, "UNKNOWN_HOST", "Unknown host");
                }
                if (!seenHosts.add(g.hostId())) {
                    throw duplicate();
                }
            } else {
                if (deployments.hostIdOf(g.deploymentId()).isEmpty()) {
                    throw new PermissionException(HttpStatus.BAD_REQUEST, "UNKNOWN_DEPLOYMENT", "Unknown deployment");
                }
                if (!seenDeployments.add(g.deploymentId())) {
                    throw duplicate();
                }
            }
            entities.add(new Permission(userId, teamId, g.hostId(), g.deploymentId(), g.level(), clock.instant()));
        }
        return entities;
    }

    @Transactional(readOnly = true)
    public PermissionsResponse getForTeam(UUID teamId) {
        findTeam(teamId);
        return teamResponse(teamId);
    }

    /** Who may access the host: direct user grants and team grants, with names and levels only. */
    @Transactional(readOnly = true)
    public SubjectGrantsResponse forHost(UUID hostId) {
        if (!hosts.existsById(hostId)) {
            throw new PermissionException(HttpStatus.NOT_FOUND, "UNKNOWN_HOST", "Unknown host");
        }
        return subjectGrants(grants.findByHostId(hostId));
    }

    @Transactional(readOnly = true)
    public SubjectGrantsResponse forDeployment(UUID deploymentId) {
        if (deployments.hostIdOf(deploymentId).isEmpty()) {
            throw new PermissionException(HttpStatus.NOT_FOUND, "UNKNOWN_DEPLOYMENT", "Unknown deployment");
        }
        return subjectGrants(grants.findByDeploymentId(deploymentId));
    }

    private SubjectGrantsResponse subjectGrants(List<Permission> permissions) {
        Map<UUID, String> userNames = users.findAllById(permissions.stream().map(Permission::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        Map<UUID, String> teamNames = teams.findAllById(permissions.stream().map(Permission::getTeamId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));
        List<SubjectGrantResponse> list = permissions.stream().map(p -> p.getUserId() != null
                        ? new SubjectGrantResponse("USER", p.getUserId(), userNames.get(p.getUserId()), p.getLevel())
                        : new SubjectGrantResponse("TEAM", p.getTeamId(), teamNames.get(p.getTeamId()), p.getLevel()))
                .sorted(Comparator.comparing(SubjectGrantResponse::subjectType)
                        .thenComparing(g -> String.valueOf(g.subjectName()), String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new SubjectGrantsResponse(list);
    }

    @Auditable(action = "TEAM_PERMISSIONS_UPDATE", targetType = "TEAM", targetId = "#teamId")
    @Transactional
    PermissionsResponse replaceForTeam(UUID teamId, List<GrantRequest> requested) {
        findTeam(teamId);
        List<Permission> entities = build(null, teamId, requested == null ? List.of() : requested);
        grants.deleteByTeamId(teamId);
        grants.flush();
        grants.saveAll(entities);
        users.findAllById(teamMembers.findByTeam(teamId).stream().map(TeamMember::getUserId).toList())
                .forEach(u -> events.publishEvent(new UserAccessChangedEvent(u.getUsername())));
        return teamResponse(teamId);
    }

    private void findTeam(UUID teamId) {
        if (!teams.existsById(teamId)) {
            throw new PermissionException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "Team not found");
        }
    }

    private PermissionsResponse teamResponse(UUID teamId) {
        return toResponse(grants.findByTeamId(teamId));
    }

    private PermissionException duplicate() {
        return new PermissionException(HttpStatus.BAD_REQUEST, "DUPLICATE_GRANT", "Duplicate grant for the same target");
    }

    private User find(UUID userId) {
        return users.findById(userId).orElseThrow(
                () -> new PermissionException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
    }

    private PermissionsResponse response(UUID userId) {
        return toResponse(grants.findByUserId(userId));
    }

    private PermissionsResponse toResponse(List<Permission> permissions) {
        List<GrantResponse> list = permissions.stream()
                .map(p -> new GrantResponse(p.getHostId(), p.getDeploymentId(),
                        p.getDeploymentId() == null ? null : deployments.hostIdOf(p.getDeploymentId()).orElse(null),
                        p.getLevel()))
                .toList();
        return new PermissionsResponse(list);
    }
}
