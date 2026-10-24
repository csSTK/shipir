package dev.bethge.shipir.registry;

import org.springframework.http.HttpStatus;

/** A registry credential request that cannot be fulfilled; carries the HTTP status and a stable error code. */
public class RegistryException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    RegistryException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
