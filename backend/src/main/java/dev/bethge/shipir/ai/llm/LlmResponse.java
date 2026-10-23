package dev.bethge.shipir.ai.llm;

import java.util.List;

/** Complete assistant turn after the stream ended: full text and assembled tool calls. */
public record LlmResponse(String content, List<LlmToolCall> toolCalls) {
}
