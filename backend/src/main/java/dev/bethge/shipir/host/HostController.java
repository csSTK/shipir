package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import dev.bethge.shipir.host.dto.ConnectionTestRequest;
import dev.bethge.shipir.host.dto.CreateHostRequest;
import dev.bethge.shipir.host.dto.HostOverviewResponse;
import dev.bethge.shipir.host.dto.HostResponse;
import dev.bethge.shipir.host.dto.SetHostTagsRequest;
import dev.bethge.shipir.host.dto.UpdateHostRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Reading is open to every role; writes are ADMIN-only, enforced in {@code SecurityConfig}. */
@RestController
@RequestMapping("/api/hosts")
class HostController {

    private final HostService service;
    private final HostOverviewService overview;

    HostController(HostService service, HostOverviewService overview) {
        this.service = service;
        this.overview = overview;
    }

    @GetMapping
    List<HostResponse> list() {
        return service.list();
    }

    /** Live state of every environment; slow or broken ones are reported as DOWN, never as an error. */
    @GetMapping("/overview")
    List<HostOverviewResponse> overview() {
        return overview.overview();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    HostResponse create(@Valid @RequestBody CreateHostRequest request, Authentication authentication) {
        return service.create(request, authentication.getName());
    }

    @PostMapping("/connection-test")
    ConnectionTestResult connectionTest(@Valid @RequestBody ConnectionTestRequest request) {
        return service.testConnection(request);
    }

    @PatchMapping("/{id}")
    HostResponse rename(@PathVariable UUID id, @Valid @RequestBody UpdateHostRequest request) {
        return service.rename(id, request.name(), request.tags());
    }

    /** Replaces the tag set (ADMIN only, enforced in {@code SecurityConfig} by the {@code /api/hosts/**} rule). */
    @PutMapping("/{id}/tags")
    HostResponse setTags(@PathVariable UUID id, @Valid @RequestBody SetHostTagsRequest request) {
        return service.setTags(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
