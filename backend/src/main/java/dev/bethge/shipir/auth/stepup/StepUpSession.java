package dev.bethge.shipir.auth.stepup;

import jakarta.servlet.http.HttpSession;

import java.time.Instant;
import java.util.Optional;

/** The identity provider's {@code auth_time} of the signed-in SSO user, kept in the HTTP session (gone with it). */
public final class StepUpSession {

    private static final String AUTH_TIME = StepUpSession.class.getName() + ".AUTH_TIME";

    private StepUpSession() {
    }

    public static void recordAuthTime(HttpSession session, Instant authTime) {
        session.setAttribute(AUTH_TIME, authTime);
    }

    /** Forgets the recorded {@code auth_time}; every sign-in calls this first, so one user's freshness never carries over to the next. */
    public static void clear(HttpSession session) {
        session.removeAttribute(AUTH_TIME);
    }

    public static Optional<Instant> authTime(HttpSession session) {
        return session != null && session.getAttribute(AUTH_TIME) instanceof Instant t ? Optional.of(t) : Optional.empty();
    }
}
