package dev.bethge.shipir.host;

import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.git.GitOperationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartException;

/** Scoped by package, so every controller in {@code host} is covered without a registration list. */
@RestControllerAdvice(basePackageClasses = HostException.class)
class HostExceptionHandler {

    @ExceptionHandler(HostException.class)
    ResponseEntity<ErrorResponse> handle(HostException ex) {
        var response = ResponseEntity.status(ex.status());
        if (ex.status() == HttpStatus.TOO_MANY_REQUESTS) {
            response = response.header("Retry-After", "5");
        }
        return response.body(new ErrorResponse(ex.code(), ex.getMessage(), ex.details()));
    }

    /**
     * Git failures from host/ callers (e.g. an image build from a Git ref) keep their own status and code, so nothing
     * depends on each caller converting them to a HostException first.
     */
    @ExceptionHandler(GitOperationException.class)
    ResponseEntity<ErrorResponse> handleGit(GitOperationException ex) {
        return ResponseEntity.status(ex.status())
                .body(new ErrorResponse(ex.code(), ex.getMessage(), java.util.List.of()));
    }

    /** The servlet container's multipart limit (see spring.servlet.multipart.*) rejected the upload. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("SIZE_LIMIT_EXCEEDED", "The transfer exceeds the size limit", java.util.List.of()));
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ErrorResponse> handleMultipart(MultipartException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_UPLOAD", "Expected a multipart request with a file part", java.util.List.of()));
    }
}
