package dev.bethge.shipir.common.exception;

/**
 * Implemented by feature exceptions that carry a stable, client-facing error code. Lets callers outside the feature
 * (e.g. the AI tool registry) report the code without knowing every feature exception type.
 */
public interface CodedException {

    String code();
}
