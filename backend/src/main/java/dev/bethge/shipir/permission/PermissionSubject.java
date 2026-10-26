package dev.bethge.shipir.permission;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;

import java.util.UUID;

/** Who is asking: user id plus global role (the role caps what a grant can allow). */
public record PermissionSubject(UUID userId, Role role) {

    /** The subject for a request: the stored role, capped when the caller authenticated with an API token. */
    public static PermissionSubject of(User user, org.springframework.security.core.Authentication caller) {
        return new PermissionSubject(user.getId(),
                dev.bethge.shipir.auth.token.ApiTokenAuthentication.effectiveRole(caller, user.getRole()));
    }

    public static PermissionSubject of(User user) {
        return new PermissionSubject(user.getId(), user.getRole());
    }
}
