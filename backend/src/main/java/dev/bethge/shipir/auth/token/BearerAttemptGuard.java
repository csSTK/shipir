package dev.bethge.shipir.auth.token;

import dev.bethge.shipir.auth.ClientAddressResolver;
import dev.bethge.shipir.auth.LoginThrottle;
import dev.bethge.shipir.auth.ThrottleLockoutAuditor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Brute-force protection for bearer credentials: every rejected {@code Authorization: Bearer} value counts as a failure
 * of the client address in {@link LoginThrottle} (scope BEARER); a rejected credential from a locked address is answered with 429 (and not
 * counted again). A valid token is never refused, so a locked address cannot be used to lock out legitimate clients behind it. The first
 * lockout is audited. Successful requests are not counted.
 */
@Component
public class BearerAttemptGuard {

    private final LoginThrottle throttle;
    private final ClientAddressResolver clientAddress;
    private final ThrottleLockoutAuditor auditor;

    BearerAttemptGuard(LoginThrottle throttle, ClientAddressResolver clientAddress, ThrottleLockoutAuditor auditor) {
        this.throttle = throttle;
        this.clientAddress = clientAddress;
        this.auditor = auditor;
    }

    /** Remaining lock time of the request's client address. */
    Optional<Duration> lockedFor(HttpServletRequest request) {
        String address = clientAddress.resolve(request);
        // The address doubles as the "user" key: a bearer attempt has no known user, and per-address is what counts.
        return throttle.lockedFor(LoginThrottle.Scope.BEARER, address, address);
    }

    void recordFailure(HttpServletRequest request) {
        String address = clientAddress.resolve(request);
        if (throttle.recordFailure(LoginThrottle.Scope.BEARER, address, address)) {
            auditor.lockoutStarted(LoginThrottle.Scope.BEARER, address, address);
        }
    }
}
