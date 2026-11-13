package dev.bethge.shipir.auth;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.dto.CurrentUserResponse;
import dev.bethge.shipir.auth.dto.LocaleRequest;
import dev.bethge.shipir.auth.dto.LoginRequest;
import dev.bethge.shipir.auth.dto.PasswordChangeRequest;
import dev.bethge.shipir.auth.dto.PasswordPolicyErrorResponse;
import dev.bethge.shipir.auth.dto.PasswordResetConfirmRequest;
import dev.bethge.shipir.auth.dto.PasswordResetRequest;
import dev.bethge.shipir.auth.stepup.StepUpSession;
import dev.bethge.shipir.auth.token.ApiTokenAuthentication;
import dev.bethge.shipir.auth.totp.TotpService;
import dev.bethge.shipir.auth.totp.dto.MfaRequiredResponse;
import dev.bethge.shipir.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    /** One body for every failed login so the response never reveals whether the user exists. */
    private static final ErrorResponse INVALID_CREDENTIALS =
            new ErrorResponse("INVALID_CREDENTIALS", "Invalid credentials");

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CsrfTokenRepository csrfTokenRepository;
    private final UserLookupService users;
    private final UserService userService;
    private final UserSetupStatus setupStatus;
    private final PasswordResetService passwordResetService;
    private final LoginThrottle throttle;
    private final ClientAddressResolver clientAddress;
    private final ThrottleLockoutAuditor lockoutAuditor;
    private final TotpService totp;
    private final LocalLoginPolicy localLoginPolicy;
    private final SessionInvalidator sessionInvalidator;

    AuthController(AuthenticationManager authenticationManager,
                   SecurityContextRepository securityContextRepository,
                   SessionAuthenticationStrategy sessionAuthenticationStrategy,
                   CsrfTokenRepository csrfTokenRepository,
                   UserLookupService users,
                   UserService userService,
                   UserSetupStatus setupStatus,
                   PasswordResetService passwordResetService,
                   LoginThrottle throttle,
                   ClientAddressResolver clientAddress,
                   ThrottleLockoutAuditor lockoutAuditor,
                   TotpService totp,
                   LocalLoginPolicy localLoginPolicy,
                   SessionInvalidator sessionInvalidator) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.csrfTokenRepository = csrfTokenRepository;
        this.users = users;
        this.userService = userService;
        this.setupStatus = setupStatus;
        this.passwordResetService = passwordResetService;
        this.throttle = throttle;
        this.clientAddress = clientAddress;
        this.lockoutAuditor = lockoutAuditor;
        this.totp = totp;
        this.localLoginPolicy = localLoginPolicy;
        this.sessionInvalidator = sessionInvalidator;
    }

    @PostMapping("/login")
    // A 2FA user's password step is only half a login: marked mfaPending in the details; the completed login is MFA_VERIFY.
    @Auditable(action = "LOGIN", targetType = "USER", username = "#body.username()",
            details = "#result.body instanceof T(dev.bethge.shipir.auth.totp.dto.MfaRequiredResponse) ? {'mfaPending': true} : null")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest body,
                            HttpServletRequest request, HttpServletResponse response) {
        String address = clientAddress.resolve(request);
        // Admitting counts the attempt at once (atomically with the lock check); a success takes it back.
        LoginThrottle.Admission admission = throttle.admit(LoginThrottle.Scope.LOGIN, body.username(), address);
        if (!admission.admitted()) {
            return tooManyAttempts(admission.lockedFor().get());
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        } catch (AuthenticationException ex) {
            auditLockout(admission, LoginThrottle.Scope.LOGIN, body.username(), address);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID_CREDENTIALS);
        }
        // Checked after the password: a wrong password stays an ordinary 401 whoever the user is. A refused local
        // login is neither a failure nor a success for the throttle: the counter is left as it is.
        User user = users.findByUsername(authentication.getName()).orElse(null);
        if (user == null) {
            auditLockout(admission, LoginThrottle.Scope.LOGIN, body.username(), address);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID_CREDENTIALS);
        }
        if (!localLoginPolicy.allows(user.getRole())) {
            throttle.release(LoginThrottle.Scope.LOGIN, body.username(), address);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("LOCAL_LOGIN_DISABLED",
                    "Password login is disabled; sign in with single sign-on"));
        }
        throttle.recordSuccess(LoginThrottle.Scope.LOGIN, body.username(), address);
        if (user.isTwoFactorEnabled()) {
            // Password is right, second factor missing: nothing is authenticated yet, the session only remembers
            // who may submit a code to /api/auth/2fa/verify.
            totp.beginPending(request, user.getUsername());
            return ResponseEntity.ok(new MfaRequiredResponse(true));
        }
        // Rotate the session id (fixation protection) and the CSRF token before the context is stored.
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        // The id rotation keeps session attributes: an earlier SSO user's auth_time must not count for this user
        StepUpSession.clear(request.getSession(true));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return ResponseEntity.ok(CurrentUserResponse.from(user, setupStatus.stepFor(user)));
    }

    @GetMapping("/me")
    ResponseEntity<?> me(Authentication authentication) {
        return users.findByUsername(authentication.getName())
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(CurrentUserResponse.from(u, setupStatus.stepFor(u), ApiTokenAuthentication.effectiveRole(authentication, u.getRole()))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("UNAUTHENTICATED", "Authentication required")));
    }

    @PutMapping("/me/locale")
    ResponseEntity<Void> changeLocale(@Valid @RequestBody LocaleRequest body, Authentication authentication) {
        userService.changeLocale(authentication.getName(), body.locale());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/password")
    @Auditable(action = "PASSWORD_CHANGE", targetType = "USER")
    ResponseEntity<?> changePassword(@Valid @RequestBody PasswordChangeRequest body, Authentication authentication,
                                     HttpServletRequest request, HttpServletResponse response) {
        // The current-password check is a guessing oracle for a stolen session: throttled like a login.
        String address = clientAddress.resolve(request);
        LoginThrottle.Admission admission = throttle.admit(LoginThrottle.Scope.PASSWORD, authentication.getName(), address);
        if (!admission.admitted()) {
            return tooManyAttempts(admission.lockedFor().get());
        }
        try {
            userService.changePassword(authentication.getName(), body.currentPassword(), body.newPassword());
        } catch (UserService.InvalidCurrentPasswordException ex) {
            auditLockout(admission, LoginThrottle.Scope.PASSWORD, authentication.getName(), address);
            throw ex;
        } catch (UserService.PasswordPolicyException ex) {
            // Current password was right, only the new one breaks the policy: not a guess. Any other failure
            // keeps the provisional count (fail closed).
            throttle.recordSuccess(LoginThrottle.Scope.PASSWORD, authentication.getName(), address);
            throw ex;
        }
        throttle.recordSuccess(LoginThrottle.Scope.PASSWORD, authentication.getName(), address);
        // Other sessions (a stolen one, say) must not survive; the current one is kept and rotated below.
        HttpSession current = request.getSession(false);
        sessionInvalidator.expireSessionsExcept(authentication.getName(),
                current == null ? null : current.getId());
        // Privilege change: rotate the session id and CSRF token; the security context stays in the session.
        // A token request has no session to rotate (the strategy would register an empty one).
        if (!(authentication instanceof ApiTokenAuthentication)) {
            sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        }
        return ResponseEntity.noContent().build();
    }

    /** Always 202, whether or not the user exists, so the response never reveals accounts. */
    @PostMapping("/password-reset/request")
    ResponseEntity<?> requestPasswordReset(@Valid @RequestBody PasswordResetRequest body, HttpServletRequest request) {
        String address = clientAddress.resolve(request);
        // Every request counts, known user or not, so the limit cannot be used to find accounts.
        LoginThrottle.Admission admission = throttle.admit(LoginThrottle.Scope.RESET, body.username(), address);
        if (!admission.admitted()) {
            return tooManyAttempts(admission.lockedFor().get());
        }
        auditLockout(admission, LoginThrottle.Scope.RESET, body.username(), address);
        passwordResetService.requestReset(body.username());
        return ResponseEntity.accepted().build();
    }

    /** Does not log the user in. */
    @PostMapping("/password-reset/confirm")
    @Auditable(action = "PASSWORD_RESET_CONFIRM", targetType = "USER", username = "#body.username()")
    ResponseEntity<?> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest body,
                                           HttpServletRequest request) {
        String address = clientAddress.resolve(request);
        LoginThrottle.Admission admission = throttle.admit(LoginThrottle.Scope.RESET, body.username(), address);
        if (!admission.admitted()) {
            return tooManyAttempts(admission.lockedFor().get());
        }
        try {
            passwordResetService.confirmReset(body.username(), body.code(), body.newPassword());
        } catch (InvalidResetCodeException ex) {
            auditLockout(admission, LoginThrottle.Scope.RESET, body.username(), address);
            throw ex;
        } catch (UserService.PasswordPolicyException ex) {
            // Code was right, only the new password breaks the policy: not a guess. Any other failure keeps
            // the provisional count (fail closed).
            throttle.recordSuccess(LoginThrottle.Scope.RESET, body.username(), address);
            throw ex;
        }
        throttle.recordSuccess(LoginThrottle.Scope.RESET, body.username(), address);
        return ResponseEntity.noContent().build();
    }

    private void auditLockout(LoginThrottle.Admission admission, LoginThrottle.Scope scope, String username, String address) {
        if (admission.lockoutStarted()) {
            lockoutAuditor.lockoutStarted(scope, username, address);
        }
    }

    private static ResponseEntity<ErrorResponse> tooManyAttempts(Duration retryAfter) {
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(new ErrorResponse("TOO_MANY_ATTEMPTS", "Too many attempts, try again later"));
    }

    @ExceptionHandler(InvalidResetCodeException.class)
    ResponseEntity<ErrorResponse> invalidResetCode() {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_RESET_CODE", "The reset code is invalid or has expired"));
    }

    @ExceptionHandler(UserService.InvalidCurrentPasswordException.class)
    ResponseEntity<ErrorResponse> invalidCurrentPassword() {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_CURRENT_PASSWORD", "The current password is incorrect"));
    }

    @ExceptionHandler(UserService.PasswordPolicyException.class)
    ResponseEntity<PasswordPolicyErrorResponse> passwordPolicy(UserService.PasswordPolicyException ex) {
        return ResponseEntity.badRequest().body(PasswordPolicyErrorResponse.of(
                ex.violations().stream().map(Enum::name).toList()));
    }

    @PostMapping("/logout")
    @Auditable(action = "LOGOUT", targetType = "USER")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        csrfTokenRepository.saveToken(null, request, response);
        return ResponseEntity.noContent().build();
    }
}
