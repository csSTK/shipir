package dev.bethge.shipir.config_editor;

import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.config_editor.dto.ConfigFileBackup;
import dev.bethge.shipir.config_editor.dto.ConfigFileResponse;
import dev.bethge.shipir.config_editor.dto.ConfigFileRestoreRequest;
import dev.bethge.shipir.config_editor.dto.ConfigFileWriteRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Reads for every role, writes for ADMIN and OPERATOR (enforced in {@code SecurityConfig}). */
@RestController
@RequestMapping("/api/hosts/{hostId}/managed-deployments/{id}/files")
class ConfigFileController {

    private final ConfigFileService service;

    ConfigFileController(ConfigFileService service) {
        this.service = service;
    }

    @GetMapping
    Object get(@PathVariable UUID hostId, @PathVariable UUID id, @RequestParam(required = false) String path) {
        return path == null ? service.tree(hostId, id) : service.read(hostId, id, path);
    }

    @PutMapping
    ConfigFileResponse write(@PathVariable UUID hostId, @PathVariable UUID id, @RequestParam String path,
                             @Valid @RequestBody ConfigFileWriteRequest request) {
        return service.write(hostId, id, path, request.content());
    }

    @DeleteMapping
    ResponseEntity<Void> delete(@PathVariable UUID hostId, @PathVariable UUID id, @RequestParam String path) {
        service.delete(hostId, id, path);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/backups")
    List<ConfigFileBackup> backups(@PathVariable UUID hostId, @PathVariable UUID id, @RequestParam String path) {
        return service.backups(hostId, id, path);
    }

    @PostMapping("/restore")
    ConfigFileResponse restore(@PathVariable UUID hostId, @PathVariable UUID id, @RequestParam String path,
                               @Valid @RequestBody ConfigFileRestoreRequest request) {
        return service.restore(hostId, id, path, request.backupId());
    }

    @ExceptionHandler(ConfigFileException.class)
    ResponseEntity<ErrorResponse> handle(ConfigFileException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(DeploymentException.class)
    ResponseEntity<ErrorResponse> handle(DeploymentException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage(), ex.details()));
    }
}
