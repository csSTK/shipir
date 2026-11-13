package dev.bethge.shipir.auth.stepup;

import org.springframework.http.HttpStatus;

import java.time.Duration;

/** A step-up that did not pass: HTTP status, stable error code, and for 429 the time left on the lockout. */
public class StepUpException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Duration retryAfter;

    StepUpException(HttpStatus status, String code, String message, Duration retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    StepUpException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    /** Null unless this is a 429. */
    Duration retryAfter() {
        return retryAfter;
    }
}
