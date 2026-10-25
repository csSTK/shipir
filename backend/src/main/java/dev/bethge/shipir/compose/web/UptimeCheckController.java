package dev.bethge.shipir.compose.web;

import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.compose.UptimeCheckService;
import dev.bethge.shipir.compose.dto.UptimeCheckRequest;
import dev.bethge.shipir.compose.dto.UptimeCheckResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/** HTTP uptime checks of a managed deployment. ADMIN and OPERATOR (the managed-deployments rule), OPERATE permission. */
@RestController
@RequestMapping("/api/hosts/{hostId}/managed-deployments/{id}/uptime-checks")
class UptimeCheckController {

    private final UptimeCheckService service;

    UptimeCheckController(UptimeCheckService service) {
        this.service = service;
    }

    @GetMapping
    List<UptimeCheckResponse> list(@PathVariable UUID hostId, @PathVariable UUID id) {
        return service.list(hostId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UptimeCheckResponse create(@PathVariable UUID hostId, @PathVariable UUID id, @Valid @RequestBody UptimeCheckRequest request) {
        return service.create(hostId, id, request);
    }

    @PutMapping("/{checkId}")
    UptimeCheckResponse update(@PathVariable UUID hostId, @PathVariable UUID id, @PathVariable UUID checkId,
                               @Valid @RequestBody UptimeCheckRequest request) {
        return service.update(hostId, id, checkId, request);
    }

    @DeleteMapping("/{checkId}")
    ResponseEntity<Void> delete(@PathVariable UUID hostId, @PathVariable UUID id, @PathVariable UUID checkId) {
        service.delete(hostId, id, checkId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(DeploymentException.class)
    ResponseEntity<ErrorResponse> handle(DeploymentException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage(), e.details()));
    }
}
