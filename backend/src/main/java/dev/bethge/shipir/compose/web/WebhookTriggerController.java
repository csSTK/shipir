package dev.bethge.shipir.compose.web;

import dev.bethge.shipir.auth.ClientAddressResolver;
import dev.bethge.shipir.auth.LoginThrottle;
import dev.bethge.shipir.auth.ThrottleLockoutAuditor;
import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.compose.DeploymentWebhookService;
import dev.bethge.shipir.compose.dto.DeploymentRunResponse;
import dev.bethge.shipir.git.GitOperationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Optional;

/**
 * Public redeploy webhook: unauthenticated and CSRF-exempt (see {@code SecurityConfig}); the secret in the path is the
 * only credential. Unknown, disabled and rotated-away tokens give the same 404. Each unknown token counts as a failure
 * of the client address in {@link LoginThrottle}; a locked address gets 429 before the token is looked at, so the
 * answer reveals nothing about the token. The start of a lockout is audited (LOGIN_THROTTLED, scope and address).
 */
@RestController
@RequestMapping("/api/webhooks/deploy")
class WebhookTriggerController {

    private final DeploymentWebhookService webhooks;
    private final LoginThrottle throttle;
    private final ClientAddressResolver clientAddress;
    private final ThrottleLockoutAuditor lockoutAuditor;

    WebhookTriggerController(DeploymentWebhookService webhooks, LoginThrottle throttle, ClientAddressResolver clientAddress,
                             ThrottleLockoutAuditor lockoutAuditor) {
        this.lockoutAuditor = lockoutAuditor;
        this.webhooks = webhooks;
        this.throttle = throttle;
        this.clientAddress = clientAddress;
    }

    @PostMapping("/{token}")
    ResponseEntity<?> trigger(@PathVariable String token, HttpServletRequest request) {
        String address = clientAddress.resolve(request);
        // The address doubles as the "user" key: the webhook has no user, and per-address is the limit that counts.
        Optional<Duration> locked = throttle.lockedFor(LoginThrottle.Scope.WEBHOOK, address, address);
        if (locked.isPresent()) {
            long seconds = Math.max(1, (locked.get().toMillis() + 999) / 1000);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                    .body(new ErrorResponse("TOO_MANY_ATTEMPTS", "Too many attempts, try again later"));
        }
        try {
            DeploymentRunResponse run = webhooks.trigger(token);
            return ResponseEntity.accepted().body(run);
        } catch (DeploymentException e) {
            if ("WEBHOOK_NOT_FOUND".equals(e.code())) {
                if (throttle.recordFailure(LoginThrottle.Scope.WEBHOOK, address, address)) {
                    lockoutAuditor.lockoutStarted(LoginThrottle.Scope.WEBHOOK, address, address);
                }
            }
            throw e;
        }
    }

    @ExceptionHandler(DeploymentException.class)
    ResponseEntity<ErrorResponse> handle(DeploymentException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage(), ex.details()));
    }

    @ExceptionHandler(GitOperationException.class)
    ResponseEntity<ErrorResponse> handle(GitOperationException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
