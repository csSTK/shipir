package dev.bethge.shipir.ai.settings;

import dev.bethge.shipir.ai.settings.dto.AiConnectionTestRequest;
import dev.bethge.shipir.ai.settings.dto.AiConnectionTestResponse;
import dev.bethge.shipir.ai.settings.dto.AiSettingsRequest;
import dev.bethge.shipir.ai.settings.dto.AiSettingsResponse;
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
@RequestMapping("/api/settings/ai")
class AiSettingsController {

    private final AiSettingsService service;

    AiSettingsController(AiSettingsService service) {
        this.service = service;
    }

    @GetMapping
    AiSettingsResponse get() {
        return service.get();
    }

    @PutMapping
    AiSettingsResponse update(@Valid @RequestBody AiSettingsRequest request, Authentication authentication) {
        return service.update(request, authentication.getName());
    }

    @PostMapping("/test")
    AiConnectionTestResponse test(@RequestBody(required = false) AiConnectionTestRequest request) {
        return service.test(request == null ? null : request.provider());
    }

    @ExceptionHandler(AiSettingsException.class)
    ResponseEntity<ErrorResponse> handle(AiSettingsException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
