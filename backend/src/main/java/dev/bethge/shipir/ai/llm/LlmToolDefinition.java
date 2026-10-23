package dev.bethge.shipir.ai.llm;

import java.util.Map;

/** A tool offered to the model; {@code parameters} is a JSON Schema object. */
public record LlmToolDefinition(String name, String description, Map<String, Object> parameters) {
}
