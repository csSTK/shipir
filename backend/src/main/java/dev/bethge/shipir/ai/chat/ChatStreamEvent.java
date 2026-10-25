package dev.bethge.shipir.ai.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Live frame on {@code /topic/chat/{sessionId}}. TOKEN carries a text delta; TOOL_CALL/TOOL_RESULT name the tool;
 * PENDING_ACTION announces a write waiting for confirmation ({@code messageId}); the run ends with DONE
 * ({@code outcome} COMPLETE, PAUSED or ERROR, plus the error code in {@code text}). Stored messages are the source
 * of truth; the stream only makes them appear sooner. RATE_LIMIT_WAIT means the run waits {@code seconds} (whole,
 * at least 1) for the provider's rate limit and then retries; it is followed by more events or DONE.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatStreamEvent(UUID runId, Type type, String text, String toolName, UUID messageId, String outcome,
                              Integer seconds) {

    public enum Type { TOKEN, TOOL_CALL, TOOL_RESULT, PENDING_ACTION, RATE_LIMIT_WAIT, DONE }

    static ChatStreamEvent token(UUID runId, String text) {
        return new ChatStreamEvent(runId, Type.TOKEN, text, null, null, null, null);
    }

    static ChatStreamEvent toolCall(UUID runId, String toolName) {
        return new ChatStreamEvent(runId, Type.TOOL_CALL, null, toolName, null, null, null);
    }

    static ChatStreamEvent toolResult(UUID runId, String toolName, boolean error) {
        return new ChatStreamEvent(runId, Type.TOOL_RESULT, null, toolName, null, error ? "ERROR" : "OK", null);
    }

    static ChatStreamEvent pending(UUID runId, String toolName, String description, UUID messageId) {
        return new ChatStreamEvent(runId, Type.PENDING_ACTION, description, toolName, messageId, null, null);
    }

    static ChatStreamEvent rateLimitWait(UUID runId, int seconds) {
        return new ChatStreamEvent(runId, Type.RATE_LIMIT_WAIT, null, null, null, null, seconds);
    }

    static ChatStreamEvent done(UUID runId, String outcome, String errorCode) {
        return new ChatStreamEvent(runId, Type.DONE, errorCode, null, null, outcome, null);
    }
}
