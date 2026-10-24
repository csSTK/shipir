package dev.bethge.shipir.permission;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.permission.dto.GrantRequest;
import dev.bethge.shipir.permission.dto.GrantResponse;
import dev.bethge.shipir.permission.dto.PermissionsResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** ADMIN management of a user's grants. PUT replaces the complete set. */
@Service
class PermissionGrantService {

    private final PermissionRepository grants;
    private final UserRepository users;
    private final DockerHostRepository hosts;
    private final DeploymentDirectory deployments;
    private final Clock clock;

    PermissionGrantService(PermissionRepository grants, UserRepository users, DockerHostRepository hosts,
                           DeploymentDirectory deployments, Clock clock) {
        this.grants = grants;
        this.users = users;
        this.hosts = hosts;
        this.deployments = deployments;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    PermissionsResponse get(UUID userId) {
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
            entities.add(new Permission(userId, g.hostId(), g.deploymentId(), g.level(), clock.instant()));
        }
        grants.deleteByUserId(userId);
        grants.flush();
        grants.saveAll(entities);
        return response(userId);
    }

    private PermissionException duplicate() {
        return new PermissionException(HttpStatus.BAD_REQUEST, "DUPLICATE_GRANT", "Duplicate grant for the same target");
    }

    private User find(UUID userId) {
        return users.findById(userId).orElseThrow(
                () -> new PermissionException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
    }

    private PermissionsResponse response(UUID userId) {
        List<GrantResponse> list = grants.findByUserId(userId).stream()
                .map(p -> new GrantResponse(p.getHostId(), p.getDeploymentId(),
                        p.getDeploymentId() == null ? null : deployments.hostIdOf(p.getDeploymentId()).orElse(null),
                        p.getLevel()))
                .toList();
        return new PermissionsResponse(list);
    }
}
