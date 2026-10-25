package dev.bethge.shipir.auth;

import org.springframework.stereotype.Component;

/** Answers whether a user is the only enabled ADMIN, so no role change, disable or delete can lock everyone out. */
@Component
public class LastAdminGuard {

    private final UserRepository users;

    LastAdminGuard(UserRepository users) {
        this.users = users;
    }

    /** True if no other enabled ADMIN exists besides {@code target}. */
    public boolean isOnlyEnabledAdmin(User target) {
        return users.countByRoleAndDisabledAtIsNullAndIdNot(Role.ADMIN, target.getId()) == 0;
    }
}
