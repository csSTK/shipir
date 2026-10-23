package dev.bethge.shipir.ai.settings;

import org.springframework.http.HttpStatus;

/** An AI settings request that cannot be fulfilled; carries the HTTP status and a stable error code. */
class AiSettingsException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    AiSettingsException(HttpStatus status, String code, String message) {
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
