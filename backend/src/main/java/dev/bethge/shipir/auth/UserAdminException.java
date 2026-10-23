package dev.bethge.shipir.auth;

import org.springframework.http.HttpStatus;

class UserAdminException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    UserAdminException(HttpStatus status, String code, String message) {
        super(message, null, false, false);
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
