package dev.bethge.shipir.auth.oidc;

import org.springframework.http.HttpStatus;

/** An OIDC request that cannot be fulfilled: an API status (settings) or a redirect error code (browser flow). */
class OidcException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    OidcException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** Browser-flow failure; the code goes to the login page as {@code oidc_error}. */
    OidcException(String code, String message) {
        this(HttpStatus.UNAUTHORIZED, code, message);
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
