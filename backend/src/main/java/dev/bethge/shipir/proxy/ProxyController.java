package dev.bethge.shipir.proxy;

import dev.bethge.shipir.proxy.dto.ProxyManagedResponse;
import dev.bethge.shipir.proxy.dto.ProxySetupRequest;
import dev.bethge.shipir.proxy.dto.ProxySetupResponse;
import dev.bethge.shipir.proxy.dto.ProxyUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The managed reverse proxy of a host. GET is open to every role that sees the host; POST, PUT and DELETE are ADMIN
 * only ({@code /api/hosts/**} writes in {@code SecurityConfig}) and additionally need OPERATE on the host.
 */
@RestController
@RequestMapping("/api/hosts/{hostId}/proxy/managed")
class ProxyController {

    private final ProxySetupService service;

    ProxyController(ProxySetupService service) {
        this.service = service;
    }

    @GetMapping
    ProxyManagedResponse get(@PathVariable UUID hostId) {
        return service.view(hostId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProxySetupResponse setup(@PathVariable UUID hostId, @Valid @RequestBody ProxySetupRequest request,
                             Authentication authentication) {
        return service.setup(hostId, request, authentication.getName());
    }

    @PutMapping
    ProxySetupResponse update(@PathVariable UUID hostId, @Valid @RequestBody ProxyUpdateRequest request,
                              Authentication authentication) {
        return service.update(hostId, request, authentication.getName());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID hostId) {
        service.remove(hostId);
    }
}
