package dev.bethge.shipir.streaming;

import org.springframework.http.HttpStatus;

/** An exec request that cannot be fulfilled; carries the HTTP status and a stable error code. */
public class ExecException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    ExecException(HttpStatus status, String code, String message) {
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
