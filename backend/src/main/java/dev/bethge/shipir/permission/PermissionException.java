package dev.bethge.shipir.permission;

import org.springframework.http.HttpStatus;

public class PermissionException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    PermissionException(HttpStatus status, String code, String message) {
        super(message, null, false, false);
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
