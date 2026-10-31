package dev.bethge.shipir.auth.totp;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.dto.CurrentUserResponse;
import dev.bethge.shipir.auth.totp.dto.CodeRequest;
import dev.bethge.shipir.auth.totp.dto.DisableRequest;
import dev.bethge.shipir.auth.totp.dto.RecoveryCodesResponse;
import dev.bethge.shipir.auth.totp.dto.SetupResponse;
import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.common.security.SetupStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/auth/2fa/*}. {@code verify} is permitted without authentication in {@code SecurityConfig} but only
 * works for a session whose password step succeeded ({@link TotpService#pendingUser}); the rest need a login.
 */
@RestController
@RequestMapping("/api/auth/2fa")
class TotpController {

    private static final ErrorResponse INVALID_CODE = new ErrorResponse("INVALID_CODE", "The code is invalid or has expired");

    private final TotpService totp;
    private final UserDetailsService userDetailsService;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SetupStatus setupStatus;

    TotpController(TotpService totp, UserDetailsService userDetailsService,
                   SecurityContextRepository securityContextRepository,
                   SessionAuthenticationStrategy sessionAuthenticationStrategy, SetupStatus setupStatus) {
        this.totp = totp;
        this.userDetailsService = userDetailsService;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.setupStatus = setupStatus;
    }

    @PostMapping("/setup")
    SetupResponse setup(Authentication authentication) {
        return totp.setup(authentication.getName());
    }

    @PostMapping("/confirm")
    @Auditable(action = "MFA_ENABLE", targetType = "USER")
    RecoveryCodesResponse confirm(@Valid @RequestBody CodeRequest body, Authentication authentication) {
        return new RecoveryCodesResponse(totp.confirm(authentication.getName(), body.code()));
    }

    @PostMapping("/disable")
    @Auditable(action = "MFA_DISABLE", targetType = "USER")
    ResponseEntity<Void> disable(@Valid @RequestBody DisableRequest body, Authentication authentication) {
        totp.disable(authentication.getName(), body.password(), body.code());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify")
    @Auditable(action = "MFA_VERIFY", targetType = "USER",
            username = "#request.getSession(false)?.getAttribute('shipir.mfa.pending.user')")
    ResponseEntity<?> verify(@Valid @RequestBody CodeRequest body, HttpServletRequest request,
                             HttpServletResponse response) {
        String username = totp.pendingUser(request).orElse(null);
        if (username == null) {
            return unauthenticated();
        }
        if (!totp.verifyLogin(username, body.code())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID_CODE);
        }
        UserDetails details = userDetailsService.loadUserByUsername(username);
        User user = totp.findUser(username).orElse(null);
        if (user == null || !details.isEnabled()) {
            return unauthenticated();
        }
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                details, null, details.getAuthorities());
        // Same as the password-only login: rotate session id + CSRF token, then store the security context.
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        totp.clearPending(request);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return ResponseEntity.ok(CurrentUserResponse.from(user, setupStatus.currentStep(username)));
    }

    private static ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHENTICATED", "Authentication required"));
    }

    @ExceptionHandler(TwoFactorException.class)
    ResponseEntity<ErrorResponse> handle(TwoFactorException ex) {
        var response = ResponseEntity.status(ex.status());
        if (ex instanceof TwoFactorLockedException locked) {
            response = response.header(HttpHeaders.RETRY_AFTER, Long.toString(locked.retryAfterSeconds()));
        }
        return response.body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(EncryptionKeyMissingException.class)
    ResponseEntity<ErrorResponse> keyMissing() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("ENCRYPTION_KEY_MISSING",
                "Two-factor authentication needs an encryption key on the server"));
    }

    /** Stored secret can't be decrypted (encryption key changed): clear 409 instead of 500; an ADMIN resets 2FA. */
    @ExceptionHandler(SecretDecryptionException.class)
    ResponseEntity<ErrorResponse> secretUnreadable() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("TWO_FACTOR_SECRET_UNREADABLE",
                "The stored two-factor secret cannot be read (the encryption key changed). Ask an administrator to reset two-factor authentication"));
    }
}
