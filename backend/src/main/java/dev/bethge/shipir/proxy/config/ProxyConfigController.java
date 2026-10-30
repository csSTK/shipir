package dev.bethge.shipir.proxy.config;

import dev.bethge.shipir.proxy.config.dto.CustomConfigRequest;
import dev.bethge.shipir.proxy.config.dto.CustomConfigVersionView;
import dev.bethge.shipir.proxy.config.dto.ProxyConfigResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Raw proxy configuration (Advanced mode). ADMIN only, reads included: the generated files show every hostname and
 * the custom file may hold anything the proxy accepts. The service checks the role and OPERATE on the host.
 */
@RestController
@RequestMapping("/api/hosts/{hostId}/proxy/config")
class ProxyConfigController {

    private final ProxyCustomConfigService service;

    ProxyConfigController(ProxyCustomConfigService service) {
        this.service = service;
    }

    @GetMapping
    ProxyConfigResponse get(@PathVariable UUID hostId) {
        return service.view(hostId);
    }

    @PutMapping("/custom")
    ProxyConfigResponse update(@PathVariable UUID hostId, @Valid @RequestBody CustomConfigRequest request,
                               Authentication authentication) {
        return service.update(hostId, request, authentication.getName());
    }

    @GetMapping("/custom/versions")
    List<CustomConfigVersionView> versions(@PathVariable UUID hostId) {
        return service.versions(hostId);
    }

    @PostMapping("/custom/restore/{versionId}")
    ProxyConfigResponse restore(@PathVariable UUID hostId, @PathVariable UUID versionId, Authentication authentication) {
        return service.restore(hostId, versionId, authentication.getName());
    }
}
