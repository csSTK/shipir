package dev.bethge.shipir.git;

import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.git.dto.GitConnectionTestRequest;
import dev.bethge.shipir.git.dto.GitConnectionTestResponse;
import dev.bethge.shipir.git.dto.GitCredentialRequest;
import dev.bethge.shipir.git.dto.GitCredentialResponse;
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
@RequestMapping("/api/git/credentials")
class GitCredentialController {

    private final GitCredentialService service;

    GitCredentialController(GitCredentialService service) {
        this.service = service;
    }

    @GetMapping
    List<GitCredentialResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GitCredentialResponse create(@Valid @RequestBody GitCredentialRequest request, Authentication authentication) {
        return service.create(request, authentication.getName());
    }

    @PutMapping("/{id}")
    GitCredentialResponse update(@PathVariable UUID id, @Valid @RequestBody GitCredentialRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    GitConnectionTestResponse test(@PathVariable UUID id, @Valid @RequestBody GitConnectionTestRequest request) {
        return service.test(id, request);
    }

    @ExceptionHandler(GitException.class)
    ResponseEntity<ErrorResponse> handle(GitException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
