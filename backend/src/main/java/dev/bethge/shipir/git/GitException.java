package dev.bethge.shipir.git;

import org.springframework.http.HttpStatus;

/** A Git credential request that cannot be fulfilled; carries the HTTP status and a stable error code. */
class GitException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    GitException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
