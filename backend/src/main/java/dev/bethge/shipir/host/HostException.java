package dev.bethge.shipir.host;

import dev.bethge.shipir.common.exception.CodedException;
import org.springframework.http.HttpStatus;

import java.util.List;

/** A host request that cannot be fulfilled; carries the HTTP status and stable error code. */
public class HostException extends RuntimeException implements CodedException {

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

    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    public List<String> details() {
        return details;
    }
}
