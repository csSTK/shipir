package dev.bethge.shipir.auth.stepup;

import dev.bethge.shipir.auth.ClientAddressResolver;
import dev.bethge.shipir.auth.LoginThrottle;
import dev.bethge.shipir.auth.ThrottleLockoutAuditor;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.auth.token.ApiTokenAuthentication;
import dev.bethge.shipir.auth.totp.TotpService;
import dev.bethge.shipir.auth.totp.TwoFactorException;
import dev.bethge.shipir.auth.totp.TwoFactorLockedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Makes the signed-in user prove again, right now, that they are who the session says, before a sensitive action
 * (backup export, API token creation; reusable for others). A stolen session alone must not be enough.
 *
 * <ul>
 *   <li>Local users send {@link StepUpCredentials}: the current password and, if two-factor is on, a code. Every
 *       check counts in {@link LoginThrottle} (scope {@code STEP_UP}, per user and client address, like a login):
 *       wrong credentials give 403 {@code STEP_UP_INVALID}, a lockout 429.</li>
 *   <li>SSO users (no usable local password) re-authenticate at the provider; the session then holds the ID token's
 *       {@code auth_time} ({@link StepUpSession}) and it must be at most {@code ssoMaxAge} old, else 403
 *       {@code STEP_UP_SSO_REQUIRED}, the code that tells the UI to start the provider flow.</li>
 *   <li>API token callers are refused (403): a token is not an interactive proof of identity.</li>
 * </ul>
 * Refusals are 403, not 401: the session itself is fine, and the frontend treats every 401 as "signed out".
 * Credentials are never logged or put into audit details.
 */
@Service
@EnableConfigurationProperties(StepUpProperties.class)
public class StepUpService {

    private static final LoginThrottle.Scope SCOPE = LoginThrottle.Scope.STEP_UP;
    /** Provider clocks may run a little ahead of ours. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TotpService totp;
    private final LoginThrottle throttle;
    private final ClientAddressResolver clientAddress;
    private final ThrottleLockoutAuditor lockoutAuditor;
    private final StepUpProperties properties;
    private final Clock clock;

    StepUpService(UserRepository users, PasswordEncoder encoder, TotpService totp, LoginThrottle throttle,
                  ClientAddressResolver clientAddress, ThrottleLockoutAuditor lockoutAuditor,
                  StepUpProperties properties, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.totp = totp;
        this.throttle = throttle;
        this.clientAddress = clientAddress;
        this.lockoutAuditor = lockoutAuditor;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param credentials may be null (SSO users, or a client that sent nothing)
     * @throws StepUpException 403 STEP_UP_REQUIRED / STEP_UP_INVALID / STEP_UP_SSO_REQUIRED / STEP_UP_SESSION_REQUIRED (token),
     *                         429 while locked
     */
    public void require(Authentication authentication, HttpServletRequest request, StepUpCredentials credentials) {
        User user = signedInUser(authentication);
        if (user.isExternal()) {
            if (!isFresh(request)) {
                throw new StepUpException(HttpStatus.FORBIDDEN, "STEP_UP_SSO_REQUIRED",
                        "Sign in again with single sign-on to continue");
            }
            return;
        }
        if (credentials == null || credentials.password() == null || credentials.password().isEmpty()) {
            throw new StepUpException(HttpStatus.FORBIDDEN, "STEP_UP_REQUIRED", "Confirm your password to continue");
        }
        String username = user.getUsername();
        String address = clientAddress.resolve(request);
        LoginThrottle.Admission admission = throttle.admit(SCOPE, username, address);
        if (!admission.admitted()) {
            throw new StepUpThrottledException(admission.lockedFor().get());
        }
        if (!credentialsValid(user, credentials)) {
            // The provisional count stays: a failed attempt needs no further call.
            if (admission.lockoutStarted()) {
                lockoutAuditor.lockoutStarted(SCOPE, username, address);
            }
            throw new StepUpException(HttpStatus.FORBIDDEN, "STEP_UP_INVALID",
                    "The password or code is not correct");
        }
        throttle.recordSuccess(SCOPE, username, address);
    }

    public StepUpStatusResponse status(Authentication authentication, HttpServletRequest request) {
        User user = signedInUser(authentication);
        boolean sso = user.isExternal();
        return new StepUpStatusResponse(sso ? "SSO" : "PASSWORD", !sso && user.isTwoFactorEnabled(),
                sso && isFresh(request), properties.ssoMaxAge().toSeconds());
    }

    /** How old the provider's {@code auth_time} may be, for the provider request ({@code max_age}) and the check. */
    public Duration ssoMaxAge() {
        return properties.ssoMaxAge();
    }

    /** True if {@code authTime} is recent enough (and not in the future beyond clock skew). */
    public boolean isFresh(Instant authTime) {
        Instant now = clock.instant();
        return !authTime.isAfter(now.plus(CLOCK_SKEW)) && !authTime.isBefore(now.minus(properties.ssoMaxAge()));
    }

    private boolean isFresh(HttpServletRequest request) {
        Optional<Instant> authTime = StepUpSession.authTime(request.getSession(false));
        return authTime.isPresent() && isFresh(authTime.get());
    }

    private User signedInUser(Authentication authentication) {
        if (authentication instanceof ApiTokenAuthentication) {
            throw new StepUpException(HttpStatus.FORBIDDEN, "STEP_UP_SESSION_REQUIRED",
                    "This action needs a signed-in session, not an API token");
        }
        if (authentication == null) {
            throw new StepUpException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
        }
        return users.findByUsername(authentication.getName()).orElseThrow(() ->
                new StepUpException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required"));
    }

    private boolean credentialsValid(User user, StepUpCredentials credentials) {
        if (!encoder.matches(credentials.password(), user.getPasswordHash())) {
            return false;
        }
        if (!user.isTwoFactorEnabled()) {
            return true;
        }
        if (credentials.code() == null || credentials.code().isBlank()) {
            return false;
        }
        try {
            return totp.verifyLogin(user.getUsername(), credentials.code());
        } catch (TwoFactorLockedException e) {
            throw new StepUpThrottledException(Duration.ofSeconds(e.retryAfterSeconds()));
        } catch (TwoFactorException e) {
            return false;
        }
    }
}
