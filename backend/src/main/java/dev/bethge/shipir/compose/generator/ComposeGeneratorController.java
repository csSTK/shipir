package dev.bethge.shipir.compose.generator;

import dev.bethge.shipir.common.exception.ErrorResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/compose")
class ComposeGeneratorController {

    private final ComposeGeneratorService generator;

    private final ManagedProxyLookup managedProxies;

    ComposeGeneratorController(ComposeGeneratorService generator, ManagedProxyLookup managedProxies) {
        this.generator = generator;
        this.managedProxies = managedProxies;
    }

    @PostMapping("/generate")
    GenerateResponse generate(@Valid @RequestBody GenerateRequest request) {
        return new GenerateResponse(generator.generate(request.spec(), request.hostId() == null
                ? null : managedProxies.managedProxyType(request.hostId()).orElse(null)));
    }

    @ExceptionHandler(InvalidComposeSpecException.class)
    ResponseEntity<ErrorResponse> handleInvalid(InvalidComposeSpecException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_COMPOSE_SPEC", "Compose specification is invalid", ex.violations()));
    }

    @ExceptionHandler(ManagedProxyLookup.HostNotFoundException.class)
    ResponseEntity<ErrorResponse> handleUnknownHost(ManagedProxyLookup.HostNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("HOST_NOT_FOUND", ex.getMessage()));
    }
}
