package dev.bethge.shipir.compose.execution;

/** The compose process could not be started or was interrupted (not a non-zero exit code). */
public class ComposeExecutionException extends RuntimeException {

    public ComposeExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
