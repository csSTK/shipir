package dev.bethge.shipir.compose.portainer.web;

import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.compose.portainer.PortainerException;
import dev.bethge.shipir.compose.portainer.PortainerImportService;
import dev.bethge.shipir.compose.portainer.dto.PortainerConnectRequest;
import dev.bethge.shipir.compose.portainer.dto.PortainerStackFile;
import dev.bethge.shipir.compose.portainer.dto.PortainerStackSummary;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Import from Portainer. ADMIN and OPERATOR (SecurityConfig). POST because the token travels in the body, not the URL. */
@RestController
@RequestMapping("/api/portainer-import/stacks")
class PortainerImportController {

    private final PortainerImportService service;

    PortainerImportController(PortainerImportService service) {
        this.service = service;
    }

    @PostMapping
    List<PortainerStackSummary> list(@Valid @RequestBody PortainerConnectRequest request) {
        return service.listStacks(request.url(), request.apiToken());
    }

    @PostMapping("/{id}")
    PortainerStackFile fetch(@PathVariable long id, @Valid @RequestBody PortainerConnectRequest request) {
        return service.fetchStack(request.url(), request.apiToken(), id);
    }

    @ExceptionHandler(PortainerException.class)
    ResponseEntity<ErrorResponse> handle(PortainerException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }
}
