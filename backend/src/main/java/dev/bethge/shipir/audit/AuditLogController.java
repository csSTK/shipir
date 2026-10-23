package dev.bethge.shipir.audit;

import dev.bethge.shipir.audit.dto.AuditLogPageResponse;
import dev.bethge.shipir.common.exception.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only (enforced in SecurityConfig). {@code size} above 100 is rejected with 400, not clamped. */
@RestController
@RequestMapping("/api/audit-log")
class AuditLogController {

    private final AuditLogQueryService service;

    AuditLogController(AuditLogQueryService service) {
        this.service = service;
    }

    @GetMapping
    AuditLogPageResponse list(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "" + AuditLogQueryService.DEFAULT_SIZE) int size,
                              @RequestParam(required = false) String action,
                              @RequestParam(required = false) String username,
                              @RequestParam(required = false) String from,
                              @RequestParam(required = false) String to) {
        return service.find(page, size, action, username, from, to);
    }

    @ExceptionHandler(InvalidAuditQueryException.class)
    ResponseEntity<ErrorResponse> handleInvalid(InvalidAuditQueryException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_PARAMETER", ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_PARAMETER", ex.getName() + " has an invalid value"));
    }
}
