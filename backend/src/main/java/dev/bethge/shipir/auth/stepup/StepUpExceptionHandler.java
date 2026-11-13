package dev.bethge.shipir.auth.stepup;

import dev.bethge.shipir.common.exception.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps {@link StepUpException} to the uniform error body (with {@code Retry-After} on 429) for every guarded endpoint. */
@RestControllerAdvice
class StepUpExceptionHandler {

    @ExceptionHandler(StepUpException.class)
    ResponseEntity<ErrorResponse> handle(StepUpException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status());
        if (ex.retryAfter() != null) {
            long seconds = Math.max(1, (ex.retryAfter().toMillis() + 999) / 1000);
            response = response.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        }
        return response.body(new ErrorResponse(ex.code(), ex.getMessage()));
    }
}
