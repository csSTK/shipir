package dev.bethge.shipir.permission;

import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.permission.dto.PermissionsResponse;
import dev.bethge.shipir.permission.dto.UpdatePermissionsRequest;
import dev.bethge.shipir.permission.dto.UserTeamsResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ADMIN-only via the {@code /api/users/**} matcher in SecurityConfig. */
@RestController
class PermissionController {

    private final PermissionGrantService service;

    PermissionController(PermissionGrantService service) {
        this.service = service;
    }

    @GetMapping("/api/users/{id}/permissions")
    PermissionsResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** Teams of the user with each team's grants in one response (inherited access view). */
    @GetMapping("/api/users/{id}/teams")
    UserTeamsResponse teams(@PathVariable UUID id) {
        return service.teamsOf(id);
    }

    @PutMapping("/api/users/{id}/permissions")
    PermissionsResponse put(@PathVariable UUID id, @Valid @RequestBody UpdatePermissionsRequest body) {
        return service.replace(id, body.grants());
    }

    @ExceptionHandler(PermissionException.class)
    ResponseEntity<ErrorResponse> handle(PermissionException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
