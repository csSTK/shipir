package dev.bethge.shipir.compose.web;

import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.compose.AdoptionService;
import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.compose.dto.AdoptRequest;
import dev.bethge.shipir.compose.dto.DeploymentDetailResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Adopts a live, unmanaged Compose project; ADMIN and OPERATOR (enforced in {@code SecurityConfig}). */
@RestController
@RequestMapping("/api/hosts/{hostId}/deployments")
class AdoptionController {

    private final AdoptionService service;

    AdoptionController(AdoptionService service) {
        this.service = service;
    }

    @PostMapping("/{projectName}/adopt")
    ResponseEntity<DeploymentDetailResponse> adopt(@PathVariable UUID hostId, @PathVariable String projectName,
                                                   @Valid @RequestBody(required = false) AdoptRequest request,
                                                   Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.adopt(hostId, projectName, request, authentication.getName()));
    }

    @ExceptionHandler(DeploymentException.class)
    ResponseEntity<ErrorResponse> handle(DeploymentException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage(), ex.details()));
    }
}
