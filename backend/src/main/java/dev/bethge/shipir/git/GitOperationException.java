package dev.bethge.shipir.git;

import org.springframework.http.HttpStatus;

/**
 * A Git operation on behalf of a deployment failed. Carries the HTTP status and a stable code; the message is fixed text and
 * never contains the repository URL, token or any JGit message (they may echo credentials).
 */
public class GitOperationException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public GitOperationException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
