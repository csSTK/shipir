package dev.bethge.shipir.auth;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.audit.AuditRecord;
import dev.bethge.shipir.audit.AuditService;
import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.dto.CurrentUserResponse;
import dev.bethge.shipir.auth.dto.LoginRequest;
import dev.bethge.shipir.auth.dto.PasswordChangeRequest;
import dev.bethge.shipir.auth.dto.PasswordPolicyErrorResponse;
import dev.bethge.shipir.auth.dto.PasswordResetConfirmRequest;
import dev.bethge.shipir.auth.dto.PasswordResetRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    /** One body for every failed login so the response never reveals whether the user exists. */
    private static final ErrorResponse INVALID_CREDENTIALS =
            new ErrorResponse("INVALID_CREDENTIALS", "Invalid credentials");

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CsrfTokenRepository csrfTokenRepository;
    private final UserRepository users;
    private final UserService userService;
    private final UserSetupStatus setupStatus;
    private final PasswordResetService passwordResetService;
    private final LoginThrottle throttle;
    private final ClientAddressResolver clientAddress;
    private final AuditService auditService;

    AuthController(AuthenticationManager authenticationManager,
                   SecurityContextRepository securityContextRepository,
                   SessionAuthenticationStrategy sessionAuthenticationStrategy,
                   CsrfTokenRepository csrfTokenRepository,
                   UserRepository users,
                   UserService userService,
                   UserSetupStatus setupStatus,
                   PasswordResetService passwordResetService,
                   LoginThrottle throttle,
                   ClientAddressResolver clientAddress,
                   AuditService auditService) {
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
        this.auditService = auditService;
    }

    @PostMapping("/login")
    @Auditable(action = "LOGIN", targetType = "USER", username = "#body.username()")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest body,
                            HttpServletRequest request, HttpServletResponse response) {
        String address = clientAddress.resolve(request);
        Optional<Duration> locked = throttle.lockedFor(LoginThrottle.Scope.LOGIN, body.username(), address);
        if (locked.isPresent()) {
            return tooManyAttempts(locked.get());
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        } catch (AuthenticationException ex) {
            recordFailure(LoginThrottle.Scope.LOGIN, body.username(), address);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID_CREDENTIALS);
        }
        throttle.recordSuccess(LoginThrottle.Scope.LOGIN, body.username());
        // Rotate the session id (fixation protection) and the CSRF token before the context is stored.
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return users.findByUsername(authentication.getName())
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(CurrentUserResponse.from(u, setupStatus.stepFor(u))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID_CREDENTIALS));
    }

    @GetMapping("/me")
    ResponseEntity<?> me(Authentication authentication) {
        return users.findByUsername(authentication.getName())
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(CurrentUserResponse.from(u, setupStatus.stepFor(u))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("UNAUTHENTICATED", "Authentication required")));
    }

    @PostMapping("/password")
    @Auditable(action = "PASSWORD_CHANGE", targetType = "USER")
    ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChangeRequest body, Authentication authentication,
                                        HttpServletRequest request, HttpServletResponse response) {
        userService.changePassword(authentication.getName(), body.currentPassword(), body.newPassword());
        // Privilege change: rotate the session id and CSRF token; the security context stays in the session.
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        return ResponseEntity.noContent().build();
    }

    /** Always 202, whether or not the user exists, so the response never reveals accounts. */
    @PostMapping("/password-reset/request")
    ResponseEntity<?> requestPasswordReset(@Valid @RequestBody PasswordResetRequest body, HttpServletRequest request) {
        String address = clientAddress.resolve(request);
        Optional<Duration> locked = throttle.lockedFor(LoginThrottle.Scope.RESET, body.username(), address);
        if (locked.isPresent()) {
            return tooManyAttempts(locked.get());
        }
        // Every request counts, known user or not, so the limit cannot be used to find accounts.
        recordFailure(LoginThrottle.Scope.RESET, body.username(), address);
        passwordResetService.requestReset(body.username());
        return ResponseEntity.accepted().build();
    }

    /** Does not log the user in. */
    @PostMapping("/password-reset/confirm")
    @Auditable(action = "PASSWORD_RESET_CONFIRM", targetType = "USER", username = "#body.username()")
    ResponseEntity<?> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest body,
                                           HttpServletRequest request) {
        String address = clientAddress.resolve(request);
        Optional<Duration> locked = throttle.lockedFor(LoginThrottle.Scope.RESET, body.username(), address);
        if (locked.isPresent()) {
            return tooManyAttempts(locked.get());
        }
        try {
            passwordResetService.confirmReset(body.username(), body.code(), body.newPassword());
        } catch (InvalidResetCodeException ex) {
            recordFailure(LoginThrottle.Scope.RESET, body.username(), address);
            throw ex;
        }
        throttle.recordSuccess(LoginThrottle.Scope.RESET, body.username());
        return ResponseEntity.noContent().build();
    }

    private void recordFailure(LoginThrottle.Scope scope, String username, String address) {
        if (throttle.recordFailure(scope, username, address)) {
            try {
                auditService.record(new AuditRecord(null, username, "LOGIN_THROTTLED", AuditLogEntry.Outcome.FAILURE,
                        "USER", username, Map.of("scope", scope.name(), "clientAddress", address)));
            } catch (RuntimeException e) {
                // Same rule as AuditAspect: a failing audit write must not change the response.
                log.warn("Could not write audit entry for LOGIN_THROTTLED: {}", e.toString());
            }
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
