package dev.bethge.shipir.auth.token;

import dev.bethge.shipir.auth.token.dto.ApiTokenResponse;
import dev.bethge.shipir.auth.token.dto.CreateApiTokenRequest;
import dev.bethge.shipir.auth.token.dto.CreatedApiTokenResponse;
import dev.bethge.shipir.common.exception.ErrorResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The signed-in user's API tokens. Session-authenticated only: a token cannot list, create or revoke tokens
 * ({@code SecurityConfig} denies it; the check here is a second line of defence).
 */
@RestController
@RequestMapping("/api/account/tokens")
class ApiTokenController {

    private final ApiTokenService service;

    ApiTokenController(ApiTokenService service) {
        this.service = service;
    }

    @GetMapping
    List<ApiTokenResponse> list(Authentication authentication) {
        requireSession(authentication);
        return service.list(authentication.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreatedApiTokenResponse create(@Valid @RequestBody CreateApiTokenRequest request, Authentication authentication) {
        requireSession(authentication);
        return service.create(authentication.getName(), request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> revoke(@PathVariable UUID id, Authentication authentication) {
        requireSession(authentication);
        service.revoke(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    private static void requireSession(Authentication authentication) {
        if (authentication instanceof ApiTokenAuthentication) {
            throw new org.springframework.security.access.AccessDeniedException("Tokens cannot manage tokens");
        }
    }

    @ExceptionHandler(ApiTokenException.class)
    ResponseEntity<ErrorResponse> handle(ApiTokenException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
