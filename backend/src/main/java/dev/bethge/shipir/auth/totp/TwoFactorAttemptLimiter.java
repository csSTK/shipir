package dev.bethge.shipir.auth.totp;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory throttle for second-factor codes, keyed by username (not session, so a fresh password login does not
 * reset it): after {@code maxAttempts} wrong codes the user is locked for {@code lockout}, even for a right code.
 * {@link #remainingLockout} and {@link #recordFailure} are separate calls and are not atomic on their own: every caller in
 * {@link TotpService} runs them inside a transaction that holds the user's row lock
 * ({@code findByUsernameForUpdate}) from before the check until after the failure is recorded, so parallel
 * guesses for one user are serialized (see the parallel tests in {@code TwoFactorApiTest}). Keep it that way.
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

    /** Time left on the lockout, or {@link Duration#ZERO} when the user may try again. */
    Duration remainingLockout(String username) {
        State s = states.get(username);
        if (s == null || s.lockedUntil() == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(clock.instant(), s.lockedUntil());
        if (remaining.isPositive()) {
            return remaining;
        }
        states.remove(username, s);
        return Duration.ZERO;
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
