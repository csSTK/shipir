package dev.bethge.shipir.auth;

import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Ends all active sessions of a user: they are marked expired in the {@link SessionRegistry}, the next request
 * on each of them is answered with 401 (see the concurrent-session filter in SecurityConfig).
 */
@Component
class SessionInvalidator {

    private final SessionRegistry registry;

    SessionInvalidator(SessionRegistry registry) {
        this.registry = registry;
    }

    void expireSessionsOf(String username) {
        for (Object principal : registry.getAllPrincipals()) {
            if (principal instanceof UserDetails details && details.getUsername().equals(username)) {
                registry.getAllSessions(principal, false).forEach(s -> s.expireNow());
            }
        }
    }
}
