package dev.bethge.shipir.proxy;

import dev.bethge.shipir.common.exception.CodedException;
import org.springframework.http.HttpStatus;

import java.util.List;

/** A proxy request that cannot be fulfilled; carries the HTTP status and a stable error code. */
public class ProxyException extends RuntimeException implements CodedException {

    private final HttpStatus status;
    private final String code;
    private final List<String> details;

    public ProxyException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ProxyException(HttpStatus status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
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
