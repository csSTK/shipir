package dev.bethge.shipir.auth;

import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Ends all active sessions of a user: they are marked expired in the {@link SessionRegistry}, the next request
 * on each of them is answered with 401 (see the concurrent-session filter in SecurityConfig).
 */
@Component
public class SessionInvalidator {

    private final SessionRegistry registry;

    SessionInvalidator(SessionRegistry registry) {
        this.registry = registry;
    }

    public void expireSessionsOf(String username) {
        expireSessionsExcept(username, null);
    }

    /** Like {@link #expireSessionsOf} but keeps the session with {@code keepSessionId} (null keeps none). */
    public void expireSessionsExcept(String username, String keepSessionId) {
        for (Object principal : registry.getAllPrincipals()) {
            if (principal instanceof UserDetails details && details.getUsername().equals(username)) {
                registry.getAllSessions(principal, false).stream()
                        .filter(s -> !s.getSessionId().equals(keepSessionId))
                        .forEach(s -> s.expireNow());
            }
        }
    }
}
