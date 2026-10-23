package dev.bethge.shipir.streaming;

import dev.bethge.shipir.common.exception.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ExecController.class)
class ExecExceptionHandler {

    @ExceptionHandler(ExecException.class)
    ResponseEntity<ErrorResponse> handle(ExecException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage(), java.util.List.of()));
    }
}
