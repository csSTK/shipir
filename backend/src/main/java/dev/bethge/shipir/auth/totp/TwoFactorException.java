package dev.bethge.shipir.auth.totp;

import org.springframework.http.HttpStatus;

public class TwoFactorException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    TwoFactorException(HttpStatus status, String code, String message) {
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
