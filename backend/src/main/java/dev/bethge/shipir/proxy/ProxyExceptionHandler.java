package dev.bethge.shipir.proxy;

import dev.bethge.shipir.common.exception.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped by package, so every controller in {@code proxy} is covered without a registration list. */
@RestControllerAdvice(basePackageClasses = ProxyException.class)
class ProxyExceptionHandler {

    @ExceptionHandler(ProxyException.class)
    ResponseEntity<ErrorResponse> handle(ProxyException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage(), ex.details()));
    }
}
