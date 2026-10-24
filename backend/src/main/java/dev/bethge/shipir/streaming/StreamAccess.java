package dev.bethge.shipir.streaming;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.host.ManagedDeploymentLookup;
import dev.bethge.shipir.permission.PermissionService;
import dev.bethge.shipir.permission.PermissionSubject;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * Permission checks for STOMP SUBSCRIBE. The STOMP threads carry no SecurityContext, so the subject is resolved from
 * the principal's user name; unknown or disabled users get nothing.
 */
@Component
class StreamAccess {

    private final PermissionService permissions;
    private final UserRepository users;
    private final ManagedDeploymentLookup managedDeployments;

    StreamAccess(PermissionService permissions, UserRepository users, ManagedDeploymentLookup managedDeployments) {
        this.permissions = permissions;
        this.users = users;
        this.managedDeployments = managedDeployments;
    }

    private Optional<PermissionSubject> subject(String username) {
        return users.findByUsername(username).filter(u -> !u.isDisabled()).map(PermissionSubject::of);
    }

    /** Whole host visible (containers, logs, events). */
    boolean hostFullyVisible(String username, UUID hostId) {
        return subject(username).map(s -> permissions.hostFullyVisible(s, hostId)).orElse(false);
    }

    /** The host is visible at all (whole host or through a deployment grant). */
    boolean hostVisible(String username, UUID hostId) {
        return subject(username).map(s -> permissions.canView(s, hostId)).orElse(false);
    }

    /**
     * Compose projects whose containers the user may see on the host: empty Optional = all containers (whole host
     * visible), otherwise the names of the visible managed deployments (possibly none).
     */
    Optional<Set<String>> visibleProjects(String username, UUID hostId) {
        Optional<PermissionSubject> s = subject(username);
        if (s.isEmpty()) {
            return Optional.of(Set.of());
        }
        if (permissions.hostFullyVisible(s.get(), hostId)) {
            return Optional.empty();
        }
        Set<UUID> visible = permissions.visibleDeploymentIds(s.get(), hostId);
        return Optional.of(managedDeployments.managedDeploymentIds(hostId).entrySet().stream()
                .filter(e -> visible.contains(e.getValue())).map(java.util.Map.Entry::getKey)
                .collect(Collectors.toSet()));
    }

    /** Output of a deploy or image-pull run: the user may view the run's deployment (ADMIN: any run, even unknown ones). */
    boolean mayFollowRun(String username, Optional<ComposeOutputStreamService.RunTarget> target) {
        Optional<PermissionSubject> s = subject(username);
        if (s.isEmpty()) {
            return false;
        }
        if (s.get().role() == Role.ADMIN) {
            return true;
        }
        // a run without deployment (image pull) is host-level: the whole host must be visible
        return target.map(t -> t.deploymentId() == null
                ? permissions.hostFullyVisible(s.get(), t.hostId())
                : permissions.canView(s.get(), t.hostId(), t.deploymentId())).orElse(false);
    }
}
