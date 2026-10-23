package dev.bethge.shipir.notification;

import org.springframework.http.HttpStatus;

/** A webhook request that cannot be fulfilled; carries the HTTP status and a stable error code. */
class WebhookException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    WebhookException(HttpStatus status, String code, String message) {
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
