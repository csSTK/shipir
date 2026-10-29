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

    /** Role of an enabled user, empty for unknown or disabled ones. */
    Optional<Role> roleOf(String username) {
        return subject(username).map(PermissionSubject::role);
    }

    /**
     * May the user hold a console on a container of the host? ADMIN always; OPERATOR only when
     * {@code operatorEnabled} and with OPERATE on the host or, for a container of a managed deployment, on that
     * deployment ({@code deploymentId} null = not owned by a managed deployment, needs OPERATE on the host).
     */
    boolean mayExec(String username, UUID hostId, UUID deploymentId, boolean operatorEnabled) {
        Optional<PermissionSubject> s = subject(username);
        if (s.isEmpty()) {
            return false;
        }
        if (s.get().role() == Role.ADMIN) {
            return true;
        }
        if (s.get().role() != Role.OPERATOR || !operatorEnabled) {
            return false;
        }
        return deploymentId == null ? permissions.canOperate(s.get(), hostId)
                : permissions.canOperate(s.get(), hostId, deploymentId);
    }

    /**
     * Where the user may hold a console on {@code hostId}, for the UI to hide the action (the checks at start stay
     * authoritative): {@code hostOperable} = any container of the host (ADMIN, or OPERATE on the host), and the
     * Compose project names of managed deployments of the host the user may operate. Nothing for non-ADMIN/OPERATOR
     * or when {@code operatorEnabled} is off for an OPERATOR.
     */
    ExecScope execScope(String username, UUID hostId, boolean operatorEnabled) {
        Optional<PermissionSubject> s = subject(username);
        if (s.isEmpty()) {
            return ExecScope.NONE;
        }
        if (s.get().role() == Role.ADMIN) {
            return new ExecScope(true, java.util.List.of());
        }
        if (s.get().role() != Role.OPERATOR || !operatorEnabled || !permissions.canView(s.get(), hostId)) {
            return ExecScope.NONE;
        }
        if (permissions.canOperate(s.get(), hostId)) {
            return new ExecScope(true, java.util.List.of());
        }
        java.util.List<String> projects = managedDeployments.managedDeploymentIds(hostId).entrySet().stream()
                .filter(e -> permissions.canOperate(s.get(), hostId, e.getValue()))
                .map(java.util.Map.Entry::getKey).sorted().toList();
        return new ExecScope(false, projects);
    }

    /** Console access for the container's owner: 404 unless the container is visible to the user. */
    boolean containerVisible(String username, UUID hostId, UUID deploymentId) {
        Optional<PermissionSubject> s = subject(username);
        if (s.isEmpty()) {
            return false;
        }
        if (permissions.hostFullyVisible(s.get(), hostId)) {
            return true;
        }
        return deploymentId != null && permissions.canView(s.get(), hostId, deploymentId);
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

    /**
     * Combined logs of a Compose project: the whole host visible, or the project is a managed deployment of the
     * host the user may view. Unmanaged projects need the whole host.
     */
    boolean mayFollowProject(String username, UUID hostId, String project) {
        Optional<PermissionSubject> s = subject(username);
        if (s.isEmpty()) {
            return false;
        }
        if (permissions.hostFullyVisible(s.get(), hostId)) {
            return true;
        }
        UUID deploymentId = managedDeployments.managedDeploymentIds(hostId).get(project);
        return deploymentId != null && permissions.canView(s.get(), hostId, deploymentId);
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
