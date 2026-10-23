package dev.bethge.shipir.ai.llm;

/** Failure talking to the LLM provider. Messages never contain the API key or the provider's response body. */
public class LlmException extends RuntimeException {

    public enum Code { AUTH_FAILED, RATE_LIMITED, UNREACHABLE, TIMEOUT, BAD_RESPONSE }

    private final Code code;

    public LlmException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
