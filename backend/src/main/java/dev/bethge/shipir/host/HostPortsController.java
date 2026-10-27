package dev.bethge.shipir.host;

import dev.bethge.shipir.host.dto.HostPortsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Read-only port report of one environment, for every role that can view it. */
@RestController
@RequestMapping("/api/hosts/{id}/ports")
class HostPortsController {

    private final HostPortsService service;

    HostPortsController(HostPortsService service) {
        this.service = service;
    }

    @GetMapping("/free")
    HostPortsResponse free(@PathVariable UUID id, @RequestParam(required = false) String preferred) {
        return service.report(id, preferred);
    }
}
