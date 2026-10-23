package dev.bethge.shipir.auth;

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
    private final UserRepository users;
    private final UserService userService;
    private final UserSetupStatus setupStatus;
    private final PasswordResetService passwordResetService;

    AuthController(AuthenticationManager authenticationManager,
                   SecurityContextRepository securityContextRepository,
                   SessionAuthenticationStrategy sessionAuthenticationStrategy,
                   CsrfTokenRepository csrfTokenRepository,
                   UserRepository users,
                   UserService userService,
                   UserSetupStatus setupStatus,
                   PasswordResetService passwordResetService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.csrfTokenRepository = csrfTokenRepository;
        this.users = users;
        this.userService = userService;
        this.setupStatus = setupStatus;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest body,
                            HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        } catch (AuthenticationException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID_CREDENTIALS);
        }
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
    ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChangeRequest body, Authentication authentication,
                                        HttpServletRequest request, HttpServletResponse response) {
        userService.changePassword(authentication.getName(), body.currentPassword(), body.newPassword());
        // Privilege change: rotate the session id and CSRF token; the security context stays in the session.
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        return ResponseEntity.noContent().build();
    }

    /** Always 202, whether or not the user exists, so the response never reveals accounts. */
    @PostMapping("/password-reset/request")
    ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest body) {
        passwordResetService.requestReset(body.username());
        return ResponseEntity.accepted().build();
    }

    /** Does not log the user in. */
    @PostMapping("/password-reset/confirm")
    ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest body) {
        passwordResetService.confirmReset(body.username(), body.code(), body.newPassword());
        return ResponseEntity.noContent().build();
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
