package dev.bethge.shipir.auth;

import dev.bethge.shipir.auth.dto.ChangeRoleRequest;
import dev.bethge.shipir.auth.dto.CreateUserRequest;
import dev.bethge.shipir.auth.dto.OneTimePasswordResponse;
import dev.bethge.shipir.auth.dto.UserResponse;
import dev.bethge.shipir.common.exception.ErrorResponse;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** ADMIN-only (SecurityConfig matcher) user management. */
@RestController
@RequestMapping("/api/users")
class UserAdminController {

    private final UserAdminService service;

    UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    List<UserResponse> list() {
        return service.list();
    }

    @PostMapping
    ResponseEntity<OneTimePasswordResponse> create(@Valid @RequestBody CreateUserRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body.username(), body.role()));
    }

    @PutMapping("/{id}/role")
    UserResponse changeRole(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest body,
                            Authentication authentication) {
        return service.changeRole(id, body.role(), authentication.getName());
    }

    @PostMapping("/{id}/disable")
    UserResponse disable(@PathVariable UUID id, Authentication authentication) {
        return service.disable(id, authentication.getName());
    }

    @PostMapping("/{id}/enable")
    UserResponse enable(@PathVariable UUID id) {
        return service.enable(id);
    }

    @PostMapping("/{id}/reset-password")
    OneTimePasswordResponse resetPassword(@PathVariable UUID id, Authentication authentication) {
        return service.resetPassword(id, authentication.getName());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        service.delete(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(UserAdminException.class)
    ResponseEntity<ErrorResponse> handle(UserAdminException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
