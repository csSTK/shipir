package dev.bethge.shipir.permission;

import dev.bethge.shipir.auth.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * For {@code @PreAuthorize}: {@code @permissionEvaluator.canOperate(#hostId)} or
 * {@code .canView(#hostId, #deploymentId)}. Resolves the signed-in user from the SecurityContext; anonymous or
 * unknown users are denied.
 */
@Component("permissionEvaluator")
public class PermissionEvaluator {

    private final PermissionService permissions;
    private final UserRepository users;

    PermissionEvaluator(PermissionService permissions, UserRepository users) {
        this.permissions = permissions;
        this.users = users;
    }

    public boolean canView(UUID hostId) {
        return current() != null && permissions.canView(current(), hostId);
    }

    public boolean canOperate(UUID hostId) {
        return current() != null && permissions.canOperate(current(), hostId);
    }

    public boolean canView(UUID hostId, UUID deploymentId) {
        return current() != null && permissions.canView(current(), hostId, deploymentId);
    }

    public boolean canOperate(UUID hostId, UUID deploymentId) {
        return current() != null && permissions.canOperate(current(), hostId, deploymentId);
    }

    private PermissionSubject current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return null;
        }
        return users.findByUsername(auth.getName()).filter(u -> !u.isDisabled()).map(u -> PermissionSubject.of(u, auth))
                .orElse(null);
    }
}
