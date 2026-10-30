package dev.bethge.shipir.proxy;

import dev.bethge.shipir.proxy.dto.ProxyRouteCandidate;
import dev.bethge.shipir.proxy.dto.ProxyRouteRequest;
import dev.bethge.shipir.proxy.dto.ProxyRouteView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/**
 * Published routes of a host. GET is open to every role that sees the host; POST, PUT and DELETE need ADMIN or
 * OPERATOR ({@code SecurityConfig}) and OPERATE on the host or the container's deployment (the service).
 */
@RestController
@RequestMapping("/api/hosts/{hostId}/proxy/routes")
class ProxyRouteController {

    private final ProxyRouteService service;

    ProxyRouteController(ProxyRouteService service) {
        this.service = service;
    }

    @GetMapping
    List<ProxyRouteView> list(@PathVariable UUID hostId) {
        return service.list(hostId);
    }

    @GetMapping("/candidates")
    List<ProxyRouteCandidate> candidates(@PathVariable UUID hostId) {
        return service.candidates(hostId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProxyRouteView publish(@PathVariable UUID hostId, @Valid @RequestBody ProxyRouteRequest request) {
        return service.publish(hostId, request);
    }

    @PutMapping("/{routeId}")
    ProxyRouteView update(@PathVariable UUID hostId, @PathVariable UUID routeId,
                          @Valid @RequestBody ProxyRouteRequest request) {
        return service.update(hostId, routeId, request);
    }

    @DeleteMapping("/{routeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unpublish(@PathVariable UUID hostId, @PathVariable UUID routeId) {
        service.unpublish(hostId, routeId);
    }
}
