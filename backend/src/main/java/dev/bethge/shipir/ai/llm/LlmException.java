package dev.bethge.shipir.ai.llm;

import java.time.Duration;

/** Failure talking to the LLM provider. Messages never contain the API key or the provider's response body. */
public class LlmException extends RuntimeException {

    public enum Code { AUTH_FAILED, RATE_LIMITED, UNREACHABLE, TIMEOUT, BAD_RESPONSE }

    private final Code code;
    private final Duration retryAfter;

    public LlmException(Code code, String message) {
        this(code, message, null);
    }

    /** {@code retryAfter} is the provider's requested wait on a rate limit, {@code null} if unknown. */
    public LlmException(Code code, String message, Duration retryAfter) {
        super(message);
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    /**
     * Parses a {@code Retry-After} header given in (possibly fractional) seconds. Returns {@code null} for a
     * missing, blank, negative, non-numeric or HTTP-date value.
     */
    static Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(header.trim());
            if (!Double.isFinite(seconds) || seconds < 0) {
                return null;
            }
            return Duration.ofMillis(Math.round(seconds * 1000));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Code code() {
        return code;
    }
}
