package dev.bethge.shipir.retention;

import dev.bethge.shipir.retention.dto.RetentionSettingsRequest;
import dev.bethge.shipir.retention.dto.RetentionSettingsResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN only, enforced in {@code SecurityConfig}. */
@RestController
@RequestMapping("/api/settings/retention")
class RetentionController {

    private final RetentionService service;

    RetentionController(RetentionService service) {
        this.service = service;
    }

    @GetMapping
    RetentionSettingsResponse get() {
        return service.get();
    }

    @PutMapping
    RetentionSettingsResponse update(@Valid @RequestBody RetentionSettingsRequest request,
                                     Authentication authentication) {
        return service.update(request, authentication.getName());
    }
}
