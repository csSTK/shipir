package dev.bethge.shipir.compose.generator;

import dev.bethge.shipir.common.exception.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/compose/proxy-templates/{proxy}}: the bundled Compose file of a reverse proxy (all roles). */
@RestController
@RequestMapping("/api/compose/proxy-templates")
class ProxyTemplateController {

    private final ProxyTemplates templates;

    ProxyTemplateController(ProxyTemplates templates) {
        this.templates = templates;
    }

    @GetMapping("/{proxy}")
    GenerateResponse template(@PathVariable String proxy) {
        return new GenerateResponse(templates.yaml(proxy));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> handleUnknown(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("PROXY_TEMPLATE_NOT_FOUND", "No bundled template for this proxy"));
    }
}
