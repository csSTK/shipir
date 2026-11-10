package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.auth.dto.UserResponse;
import dev.bethge.shipir.auth.oidc.dto.LinkExternalIdentityRequest;
import dev.bethge.shipir.common.exception.ErrorResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ADMIN-only (SecurityConfig matcher {@code /api/users/**}): link or unlink a user's single sign-on identity. */
@RestController
@RequestMapping("/api/users/{id}/external-identity")
class ExternalIdentityController {

    private final ExternalIdentityService service;

    ExternalIdentityController(ExternalIdentityService service) {
        this.service = service;
    }

    @PutMapping
    UserResponse link(@PathVariable UUID id, @Valid @RequestBody LinkExternalIdentityRequest body,
                      Authentication authentication) {
        return service.link(id, body.issuer(), body.subject(), authentication.getName());
    }

    @DeleteMapping
    UserResponse unlink(@PathVariable UUID id, Authentication authentication) {
        return service.unlink(id, authentication.getName());
    }

    @ExceptionHandler(OidcException.class)
    ResponseEntity<ErrorResponse> handle(OidcException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
