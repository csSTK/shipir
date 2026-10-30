package dev.bethge.shipir.proxy;

import dev.bethge.shipir.common.exception.CodedException;
import org.springframework.http.HttpStatus;

/** A proxy request that cannot be fulfilled; carries the HTTP status and a stable error code. */
public class ProxyException extends RuntimeException implements CodedException {

    private final HttpStatus status;
    private final String code;

    public ProxyException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }
}
