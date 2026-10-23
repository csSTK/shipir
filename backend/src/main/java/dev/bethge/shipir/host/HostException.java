package dev.bethge.shipir.host;

import org.springframework.http.HttpStatus;

import java.util.List;

/** A host request that cannot be fulfilled; carries the HTTP status and stable error code. */
class HostException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<String> details;

    HostException(HttpStatus status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    HostException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    List<String> details() {
        return details;
    }
}
