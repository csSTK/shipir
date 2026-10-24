package dev.bethge.shipir.auth.totp;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory throttle for second-factor codes, keyed by username (not session, so a fresh password login does not
 * reset it): after {@code maxAttempts} wrong codes the user is locked for {@code lockout}, even for a right code.
 */
@Component
class TwoFactorAttemptLimiter {

    private record State(int failures, Instant lockedUntil) {
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final TwoFactorProperties properties;
    private final Clock clock;

    TwoFactorAttemptLimiter(TwoFactorProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    boolean isLocked(String username) {
        State s = states.get(username);
        if (s == null || s.lockedUntil() == null) {
            return false;
        }
        if (clock.instant().isBefore(s.lockedUntil())) {
            return true;
        }
        states.remove(username, s);
        return false;
    }

    void recordFailure(String username) {
        states.compute(username, (k, s) -> {
            int failures = (s == null ? 0 : s.failures()) + 1;
            return failures >= properties.maxAttempts()
                    ? new State(0, clock.instant().plus(properties.lockout()))
                    : new State(failures, null);
        });
    }

    void reset(String username) {
        states.remove(username);
    }
}
