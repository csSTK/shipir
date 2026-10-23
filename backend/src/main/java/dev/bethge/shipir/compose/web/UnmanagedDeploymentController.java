package dev.bethge.shipir.compose.web;

import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.compose.UnmanagedDeploymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Removes live, unmanaged Compose projects; ADMIN and OPERATOR (enforced in {@code SecurityConfig}). */
@RestController
@RequestMapping("/api/hosts/{hostId}/deployments")
class UnmanagedDeploymentController {

    private final UnmanagedDeploymentService service;

    UnmanagedDeploymentController(UnmanagedDeploymentService service) {
        this.service = service;
    }

    @DeleteMapping("/{projectName}")
    ResponseEntity<Void> remove(@PathVariable UUID hostId, @PathVariable String projectName,
                                @RequestParam(defaultValue = "false") boolean removeVolumes) {
        service.remove(hostId, projectName, removeVolumes);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(DeploymentException.class)
    ResponseEntity<ErrorResponse> handle(DeploymentException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage(), ex.details()));
    }
}
