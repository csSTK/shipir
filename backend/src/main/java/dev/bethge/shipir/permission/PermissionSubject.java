package dev.bethge.shipir.permission;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;

import java.util.UUID;

/** Who is asking: user id plus global role (the role caps what a grant can allow). */
public record PermissionSubject(UUID userId, Role role) {

    public static PermissionSubject of(User user) {
        return new PermissionSubject(user.getId(), user.getRole());
    }
}
