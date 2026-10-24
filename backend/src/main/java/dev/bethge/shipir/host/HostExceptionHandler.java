package dev.bethge.shipir.host;

import dev.bethge.shipir.common.exception.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice(assignableTypes = {HostController.class, HostResourcesController.class, SearchController.class,
        ContainerFilesController.class})
class HostExceptionHandler {

    @ExceptionHandler(HostException.class)
    ResponseEntity<ErrorResponse> handle(HostException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorResponse(ex.code(), ex.getMessage(), ex.details()));
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
