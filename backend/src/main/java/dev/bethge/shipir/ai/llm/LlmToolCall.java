package dev.bethge.shipir.ai.llm;

/** A tool invocation requested by the model; {@code argumentsJson} is the raw JSON object string. */
public record LlmToolCall(String id, String name, String argumentsJson) {
}
