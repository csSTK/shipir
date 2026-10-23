package dev.bethge.shipir.host;

import dev.bethge.shipir.common.exception.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {HostController.class, HostResourcesController.class})
class HostExceptionHandler {

    @ExceptionHandler(HostException.class)
    ResponseEntity<ErrorResponse> handle(HostException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage(), ex.details()));
    }
}
