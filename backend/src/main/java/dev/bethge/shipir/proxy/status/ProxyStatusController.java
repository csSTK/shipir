package dev.bethge.shipir.proxy.status;

import dev.bethge.shipir.proxy.status.dto.CertificateStatusView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Read-only certificate status of the published routes; every role that sees the host may read it. */
@RestController
@RequestMapping("/api/hosts/{hostId}/proxy/routes/status")
class ProxyStatusController {

    private final ProxyStatusService service;

    ProxyStatusController(ProxyStatusService service) {
        this.service = service;
    }

    @GetMapping
    List<CertificateStatusView> status(@PathVariable UUID hostId, @RequestParam(defaultValue = "false") boolean refresh) {
        return service.status(hostId, refresh);
    }
}
