package dev.bethge.shipir.ai.llm;

import java.util.List;

/** One chat message. {@code toolCalls} only on ASSISTANT, {@code toolCallId} only on TOOL. */
public record LlmMessage(Role role, String content, List<LlmToolCall> toolCalls, String toolCallId) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public LlmMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(Role.SYSTEM, content, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content, null, null);
    }

    public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage(Role.ASSISTANT, content, toolCalls, null);
    }

    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage(Role.TOOL, content, null, toolCallId);
    }
}
