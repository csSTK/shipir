package dev.bethge.shipir.compose.web;

import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.compose.StackException;
import dev.bethge.shipir.compose.StackService;
import dev.bethge.shipir.compose.dto.StackCreateRequest;
import dev.bethge.shipir.compose.dto.StackDetailResponse;
import dev.bethge.shipir.compose.dto.StackEnvResponse;
import dev.bethge.shipir.compose.dto.StackSummaryResponse;
import dev.bethge.shipir.compose.dto.StackUpdateRequest;
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

/** Reads for every role, writes and {@code /env} for ADMIN and OPERATOR (enforced in {@code SecurityConfig}). */
@RestController
@RequestMapping("/api/hosts/{hostId}/managed-stacks")
class StackController {

    private final StackService service;

    StackController(StackService service) {
        this.service = service;
    }

    @GetMapping
    List<StackSummaryResponse> list(@PathVariable UUID hostId) {
        return service.list(hostId);
    }

    @GetMapping("/{id}")
    StackDetailResponse get(@PathVariable UUID hostId, @PathVariable UUID id) {
        return service.get(hostId, id);
    }

    @GetMapping("/{id}/env")
    StackEnvResponse env(@PathVariable UUID hostId, @PathVariable UUID id) {
        return service.env(hostId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    StackDetailResponse create(@PathVariable UUID hostId, @Valid @RequestBody StackCreateRequest request,
                               Authentication authentication) {
        return service.create(hostId, request, authentication.getName());
    }

    @PutMapping("/{id}")
    StackDetailResponse update(@PathVariable UUID hostId, @PathVariable UUID id,
                               @Valid @RequestBody StackUpdateRequest request) {
        return service.update(hostId, id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID hostId, @PathVariable UUID id) {
        service.delete(hostId, id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(StackException.class)
    ResponseEntity<ErrorResponse> handle(StackException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage(), ex.details()));
    }
}
