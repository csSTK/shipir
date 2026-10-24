package dev.bethge.shipir.streaming;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.permission.PermissionService;
import dev.bethge.shipir.permission.PermissionSubject;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Permission checks for STOMP SUBSCRIBE. The STOMP threads carry no SecurityContext, so the subject is resolved from
 * the principal's user name; unknown or disabled users get nothing.
 */
@Component
class StreamAccess {

    private final PermissionService permissions;
    private final UserRepository users;

    StreamAccess(PermissionService permissions, UserRepository users) {
        this.permissions = permissions;
        this.users = users;
    }

    private Optional<PermissionSubject> subject(String username) {
        return users.findByUsername(username).filter(u -> !u.isDisabled()).map(PermissionSubject::of);
    }

    /** Whole host visible (containers, logs, events). */
    boolean hostFullyVisible(String username, UUID hostId) {
        return subject(username).map(s -> permissions.hostFullyVisible(s, hostId)).orElse(false);
    }

    /** Output of a deploy run: the user may view the run's deployment (ADMIN: any run, even unknown ones). */
    boolean mayFollowRun(String username, Optional<ComposeOutputStreamService.RunTarget> target) {
        Optional<PermissionSubject> s = subject(username);
        if (s.isEmpty()) {
            return false;
        }
        if (s.get().role() == Role.ADMIN) {
            return true;
        }
        return target.map(t -> permissions.canView(s.get(), t.hostId(), t.deploymentId())).orElse(false);
    }
}
