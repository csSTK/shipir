package dev.bethge.shipir.common.util;

/**
 * Thrown when a user-supplied path or name fails validation. The message never echoes the offending input.
 */
public class PathValidationException extends RuntimeException {

    public PathValidationException(String message) {
        super(message);
    }
}
