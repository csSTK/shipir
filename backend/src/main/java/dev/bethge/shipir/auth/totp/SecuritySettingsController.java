package dev.bethge.shipir.auth.totp;

import dev.bethge.shipir.auth.totp.dto.SecuritySettingsRequest;
import dev.bethge.shipir.auth.totp.dto.SecuritySettingsResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN only, enforced in {@code SecurityConfig}. */
@RestController
@RequestMapping("/api/settings/security")
class SecuritySettingsController {

    private final SecuritySettingsService service;

    SecuritySettingsController(SecuritySettingsService service) {
        this.service = service;
    }

    @GetMapping
    SecuritySettingsResponse get() {
        return service.get();
    }

    @PutMapping
    SecuritySettingsResponse update(@Valid @RequestBody SecuritySettingsRequest request,
                                    Authentication authentication) {
        return service.update(request, authentication.getName());
    }
}
