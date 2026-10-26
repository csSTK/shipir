package dev.bethge.shipir.permission;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.SystemScope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Permission checks for the signed-in user of the current thread, for feature services.
 * <ul>
 *   <li><b>Inside {@link SystemScope}</b> without authentication (scheduler, health check, auto-update): every check
 *       passes and nothing is filtered.</li>
 *   <li>Authenticated but unknown or disabled user (or anonymous): nothing is allowed.</li>
 *   <li>Neither authentication nor system scope: nothing is allowed (fail closed).</li>
 * </ul>
 * Callers map a {@code false} to their own exception: 404 when not viewable, 403 when viewable but not operable.
 */
@Component
public class CurrentAccess {

    /** A subject without grants and without privileges: used when the authentication cannot be resolved. */
    private static final PermissionSubject NOBODY = new PermissionSubject(new UUID(0, 0), Role.VIEWER);

    private final PermissionService permissions;
    private final UserRepository users;

    CurrentAccess(PermissionService permissions, UserRepository users) {
        this.permissions = permissions;
        this.users = users;
    }

    /** Empty = system caller (no authentication and {@link SystemScope} bound). */
    public Optional<PermissionSubject> subject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return SystemScope.active() ? Optional.empty() : Optional.of(NOBODY);
        }
        if (!auth.isAuthenticated() || auth.getName() == null) {
            return Optional.of(NOBODY);
        }
        return Optional.of(users.findByUsername(auth.getName()).filter(u -> !u.isDisabled())
                .map(u -> PermissionSubject.of(u, auth)).orElse(NOBODY));
    }

    /** True for ADMIN and for the system caller. */
    public boolean isAdmin() {
        return subject().map(s -> s.role() == Role.ADMIN).orElse(true);
    }

    public boolean canView(UUID hostId) {
        return subject().map(s -> permissions.canView(s, hostId)).orElse(true);
    }

    public boolean canOperate(UUID hostId) {
        return subject().map(s -> permissions.canOperate(s, hostId)).orElse(true);
    }

    public boolean canView(UUID hostId, UUID deploymentId) {
        return subject().map(s -> permissions.canView(s, hostId, deploymentId)).orElse(true);
    }

    public boolean canOperate(UUID hostId, UUID deploymentId) {
        return subject().map(s -> permissions.canOperate(s, hostId, deploymentId)).orElse(true);
    }

    public boolean hostFullyVisible(UUID hostId) {
        return subject().map(s -> permissions.hostFullyVisible(s, hostId)).orElse(true);
    }

    /** Host ids the user may see; empty Optional = no restriction (system caller or ADMIN). */
    public Optional<Set<UUID>> visibleHostIds() {
        return subject().filter(s -> s.role() != Role.ADMIN).map(permissions::visibleHostIds);
    }

    /** Managed deployment ids of the host the user may see; empty Optional = no restriction (system caller or ADMIN). */
    public Optional<Set<UUID>> visibleDeploymentIds(UUID hostId) {
        return subject().filter(s -> s.role() != Role.ADMIN).map(s -> permissions.visibleDeploymentIds(s, hostId));
    }
}
