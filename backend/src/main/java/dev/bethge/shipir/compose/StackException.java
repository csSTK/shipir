package dev.bethge.shipir.compose;

import org.springframework.http.HttpStatus;

import java.util.List;

/** A stack request that cannot be fulfilled; carries the HTTP status, a stable error code and optional detail lines. */
public class StackException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<String> details;

    public StackException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public StackException(HttpStatus status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<String> details() { return details; }
}
