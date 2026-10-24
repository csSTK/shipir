package dev.bethge.shipir.ai.llm;

/**
 * A tool invocation requested by the model; {@code argumentsJson} is the raw JSON object string.
 * {@code thoughtSignature} is an opaque Gemini token that must be echoed back with the call; null for other providers.
 */
public record LlmToolCall(String id, String name, String argumentsJson, String thoughtSignature) {

    public LlmToolCall(String id, String name, String argumentsJson) {
        this(id, name, argumentsJson, null);
    }
}
