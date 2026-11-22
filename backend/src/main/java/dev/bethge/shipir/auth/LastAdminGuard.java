package dev.bethge.shipir.auth;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers whether a user is the only enabled ADMIN, so no role change, disable or delete can lock everyone out.
 * The check row-locks all enabled ADMINs until the caller's transaction ends, so two concurrent removals
 * serialize: the second sees the first one's result instead of both counting the other as still enabled.
 */
@Component
public class LastAdminGuard {

    private final UserRepository users;

    LastAdminGuard(UserRepository users) {
        this.users = users;
    }

    /**
     * True if no other enabled ADMIN exists besides {@code target}. The caller must change the target in the
     * same transaction (the lock is held until it ends).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isOnlyEnabledAdmin(User target) {
        return users.lockEnabledAdminIds().stream().noneMatch(id -> !id.equals(target.getId()));
    }
}
