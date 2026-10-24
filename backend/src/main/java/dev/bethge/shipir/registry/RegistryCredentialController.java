package dev.bethge.shipir.registry;

import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.registry.dto.RegistryCredentialRequest;
import dev.bethge.shipir.registry.dto.RegistryCredentialResponse;
import dev.bethge.shipir.registry.dto.RegistryTestResponse;
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
@RequestMapping("/api/registries")
class RegistryCredentialController {

    private final RegistryCredentialService service;

    RegistryCredentialController(RegistryCredentialService service) {
        this.service = service;
    }

    @GetMapping
    List<RegistryCredentialResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RegistryCredentialResponse create(@Valid @RequestBody RegistryCredentialRequest request,
                                      Authentication authentication) {
        return service.create(request, authentication.getName());
    }

    @PutMapping("/{id}")
    RegistryCredentialResponse update(@PathVariable UUID id, @Valid @RequestBody RegistryCredentialRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    RegistryTestResponse test(@PathVariable UUID id) {
        return service.test(id);
    }

    @ExceptionHandler(RegistryException.class)
    ResponseEntity<ErrorResponse> handle(RegistryException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
