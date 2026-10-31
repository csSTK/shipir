package dev.bethge.shipir.auth.totp;

import dev.bethge.shipir.common.exception.RequestThrottled;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/**
 * A second-factor attempt refused because the user is locked after too many wrong codes (429). Carries the time
 * left on the lockout so the response can send {@code Retry-After}.
 */
class TwoFactorLockedException extends TwoFactorException implements RequestThrottled {

    private final Duration retryAfter;

    TwoFactorLockedException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "Too many wrong codes, try again later");
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up, at least 1 (same rounding as {@code AuthController}). */
    long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
