package dev.bethge.shipir.compose.portainer;

import org.springframework.http.HttpStatus;

/** A Portainer import request that cannot be fulfilled; fixed message, never contains the token or upstream text. */
public class PortainerException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    PortainerException(HttpStatus status, String code, String message) {
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
