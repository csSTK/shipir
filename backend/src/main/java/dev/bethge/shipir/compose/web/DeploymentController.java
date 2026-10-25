package dev.bethge.shipir.compose.web;

import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.compose.DeploymentRunService;
import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.compose.DeploymentService;
import dev.bethge.shipir.compose.DeploymentWebhookService;
import dev.bethge.shipir.compose.dto.AutoUpdateRequest;
import dev.bethge.shipir.compose.dto.DeploymentRunResponse;
import dev.bethge.shipir.compose.dto.DeploymentCreateRequest;
import dev.bethge.shipir.compose.dto.DeploymentDetailResponse;
import dev.bethge.shipir.compose.dto.DeploymentEnvResponse;
import dev.bethge.shipir.compose.dto.DeploymentSummaryResponse;
import dev.bethge.shipir.compose.dto.DeploymentUpdateRequest;
import dev.bethge.shipir.compose.dto.DeploymentVersionContentResponse;
import dev.bethge.shipir.compose.dto.DeploymentVersionResponse;
import dev.bethge.shipir.compose.dto.WebhookIssuedResponse;
import dev.bethge.shipir.compose.dto.WebhookStatusResponse;
import dev.bethge.shipir.git.GitOperationException;
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
@RequestMapping("/api/hosts/{hostId}/managed-deployments")
class DeploymentController {

    private final DeploymentService service;
    private final DeploymentRunService runs;
    private final DeploymentWebhookService webhooks;

    DeploymentController(DeploymentService service, DeploymentRunService runs, DeploymentWebhookService webhooks) {
        this.service = service;
        this.runs = runs;
        this.webhooks = webhooks;
    }

    @GetMapping
    List<DeploymentSummaryResponse> list(@PathVariable UUID hostId) {
        return service.list(hostId);
    }

    @GetMapping("/{id}")
    DeploymentDetailResponse get(@PathVariable UUID hostId, @PathVariable UUID id) {
        return service.get(hostId, id);
    }

    @GetMapping("/{id}/env")
    DeploymentEnvResponse env(@PathVariable UUID hostId, @PathVariable UUID id) {
        return service.env(hostId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DeploymentDetailResponse create(@PathVariable UUID hostId, @Valid @RequestBody DeploymentCreateRequest request,
                               Authentication authentication) {
        return service.create(hostId, request, authentication.getName());
    }

    @PutMapping("/{id}")
    DeploymentDetailResponse update(@PathVariable UUID hostId, @PathVariable UUID id,
                               @Valid @RequestBody DeploymentUpdateRequest request,
                               Authentication authentication) {
        return service.update(hostId, id, request, authentication.getName());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID hostId, @PathVariable UUID id) {
        service.delete(hostId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/deploy")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DeploymentRunResponse deploy(@PathVariable UUID hostId, @PathVariable UUID id, Authentication authentication) {
        return runs.deploy(hostId, id, authentication.getName());
    }

    @PostMapping("/{id}/pull-update")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DeploymentRunResponse pullUpdate(@PathVariable UUID hostId, @PathVariable UUID id, Authentication authentication) {
        return runs.pullUpdate(hostId, id, authentication.getName());
    }

    @PostMapping("/{id}/git/redeploy")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DeploymentRunResponse gitRedeploy(@PathVariable UUID hostId, @PathVariable UUID id, Authentication authentication) {
        return runs.gitRedeploy(hostId, id, authentication.getName());
    }

    @PutMapping("/{id}/git/auto-update")
    DeploymentDetailResponse autoUpdate(@PathVariable UUID hostId, @PathVariable UUID id,
                                   @Valid @RequestBody AutoUpdateRequest request) {
        return service.configureAutoUpdate(hostId, id, request);
    }

    @PostMapping("/{id}/stop")
    DeploymentSummaryResponse stop(@PathVariable UUID hostId, @PathVariable UUID id) {
        return runs.stop(hostId, id);
    }

    @GetMapping("/{id}/versions")
    List<DeploymentVersionResponse> versions(@PathVariable UUID hostId, @PathVariable UUID id) {
        return runs.versions(hostId, id);
    }

    @GetMapping("/{id}/versions/{versionId}")
    DeploymentVersionContentResponse version(@PathVariable UUID hostId, @PathVariable UUID id,
                                             @PathVariable UUID versionId) {
        return runs.version(hostId, id, versionId);
    }

    @PostMapping("/{id}/versions/{versionId}/rollback")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DeploymentRunResponse rollback(@PathVariable UUID hostId, @PathVariable UUID id, @PathVariable UUID versionId,
                                Authentication authentication) {
        return runs.rollback(hostId, id, versionId, authentication.getName());
    }

    /** Redeploy webhook state; never the token. */
    @GetMapping("/{id}/webhook")
    WebhookStatusResponse webhook(@PathVariable UUID hostId, @PathVariable UUID id) {
        return webhooks.status(hostId, id);
    }

    /** Enables the webhook; the token is in the response and never shown again. */
    @PutMapping("/{id}/webhook")
    WebhookIssuedResponse enableWebhook(@PathVariable UUID hostId, @PathVariable UUID id) {
        return webhooks.enable(hostId, id);
    }

    @PostMapping("/{id}/webhook/rotate")
    WebhookIssuedResponse rotateWebhook(@PathVariable UUID hostId, @PathVariable UUID id) {
        return webhooks.rotate(hostId, id);
    }

    @DeleteMapping("/{id}/webhook")
    ResponseEntity<Void> disableWebhook(@PathVariable UUID hostId, @PathVariable UUID id) {
        webhooks.disable(hostId, id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(DeploymentException.class)
    ResponseEntity<ErrorResponse> handle(DeploymentException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage(), ex.details()));
    }

    @ExceptionHandler(GitOperationException.class)
    ResponseEntity<ErrorResponse> handle(GitOperationException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
