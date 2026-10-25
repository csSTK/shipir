package dev.bethge.shipir.auth.token;

import org.springframework.http.HttpStatus;

/** A token request that cannot be fulfilled; carries the HTTP status and a stable error code. */
public class ApiTokenException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    ApiTokenException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
