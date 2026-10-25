package dev.bethge.shipir.auth;

import dev.bethge.shipir.common.security.UserAccessChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * Ends all active sessions of a user: they are marked expired in the {@link SessionRegistry}, the next request
 * on each of them is answered with 401 (see the concurrent-session filter in SecurityConfig). Marking a session
 * expired does not touch WebSockets or exec consoles that are already open, so a {@link UserAccessChangedEvent}
 * is published as well; the streaming module re-checks the user's open channels on it.
 */
@Component
public class SessionInvalidator {

    private final SessionRegistry registry;
    private final ApplicationEventPublisher events;

    SessionInvalidator(SessionRegistry registry, ApplicationEventPublisher events) {
        this.registry = registry;
        this.events = events;
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
        events.publishEvent(new UserAccessChangedEvent(username));
    }
}
