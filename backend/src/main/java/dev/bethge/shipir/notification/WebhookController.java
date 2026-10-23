package dev.bethge.shipir.notification;

import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.notification.dto.WebhookRequest;
import dev.bethge.shipir.notification.dto.WebhookResponse;
import dev.bethge.shipir.notification.dto.WebhookTestResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** ADMIN only, enforced in {@code SecurityConfig}. */
@RestController
@RequestMapping("/api/notifications/webhooks")
class WebhookController {

    private final WebhookService service;

    WebhookController(WebhookService service) {
        this.service = service;
    }

    @GetMapping
    List<WebhookResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    WebhookResponse create(@Valid @RequestBody WebhookRequest request, Authentication authentication) {
        return service.create(request, authentication.getName());
    }

    @PutMapping("/{id}")
    WebhookResponse update(@PathVariable UUID id, @Valid @RequestBody WebhookRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    WebhookTestResponse test(@PathVariable UUID id) {
        return service.test(id);
    }

    @ExceptionHandler(WebhookException.class)
    ResponseEntity<ErrorResponse> handle(WebhookException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
