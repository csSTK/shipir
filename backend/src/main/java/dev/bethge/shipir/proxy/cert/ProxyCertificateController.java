package dev.bethge.shipir.proxy.cert;

import dev.bethge.shipir.proxy.cert.dto.ProxyCertificateRenewRequest;
import dev.bethge.shipir.proxy.cert.dto.ProxyCertificateRequest;
import dev.bethge.shipir.proxy.cert.dto.ProxyCertificateView;
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

/** Uploaded certificates of a host's proxy. GET: every role that sees the host; writes ADMIN (SecurityConfig fallback) + OPERATE. */
@RestController
@RequestMapping("/api/hosts/{hostId}/proxy/certificates")
class ProxyCertificateController {

    private final ProxyCertificateService service;

    ProxyCertificateController(ProxyCertificateService service) {
        this.service = service;
    }

    @GetMapping
    List<ProxyCertificateView> list(@PathVariable UUID hostId) {
        return service.list(hostId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProxyCertificateView upload(@PathVariable UUID hostId, @Valid @RequestBody ProxyCertificateRequest request) {
        return service.upload(hostId, request);
    }

    @PutMapping("/{certificateId}")
    ProxyCertificateView renew(@PathVariable UUID hostId, @PathVariable UUID certificateId,
                               @Valid @RequestBody ProxyCertificateRenewRequest request) {
        return service.renew(hostId, certificateId, request);
    }

    @DeleteMapping("/{certificateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID hostId, @PathVariable UUID certificateId) {
        service.delete(hostId, certificateId);
    }
}
