package dev.bethge.shipir.streaming;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.docker.ViewerErrorCodes;
import org.springframework.stereotype.Component;

/**
 * Decides how much Docker failure detail a stream frame may carry for the subscriber it goes to. Streams are shared
 * between subscribers and workers run on their own threads, so the ADMIN flag is read once when the subscriber
 * subscribes (there is no request context later) and the code is chosen per recipient with {@link ViewerErrorCodes}.
 */
@Component
class StreamViewers {

    private final StreamAccess access;

    StreamViewers(StreamAccess access) {
        this.access = access;
    }

    /** True only for an enabled ADMIN; unknown or disabled users are not. */
    boolean isAdmin(String username) {
        return access.roleOf(username).filter(r -> r == Role.ADMIN).isPresent();
    }
}
