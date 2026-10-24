package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.auth.oidc.dto.OidcSettingsRequest;
import dev.bethge.shipir.auth.oidc.dto.OidcSettingsResponse;
import dev.bethge.shipir.auth.oidc.dto.OidcTestResponse;
import dev.bethge.shipir.common.exception.ErrorResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN only, enforced in {@code SecurityConfig}. */
@RestController
@RequestMapping("/api/settings/oidc")
class OidcSettingsController {

    private final OidcSettingsService service;

    OidcSettingsController(OidcSettingsService service) {
        this.service = service;
    }

    @GetMapping
    OidcSettingsResponse get() {
        return service.get();
    }

    @PutMapping
    OidcSettingsResponse update(@Valid @RequestBody OidcSettingsRequest request, Authentication authentication) {
        return service.update(request, authentication.getName());
    }

    @PostMapping("/test")
    OidcTestResponse test() {
        return service.test();
    }

    @ExceptionHandler(OidcException.class)
    ResponseEntity<ErrorResponse> handle(OidcException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
