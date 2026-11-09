package dev.bethge.shipir.ai.chat.dto;

import java.util.List;

/** {@code running}: an assistant run is active for this session right now (so a reloaded client can follow it). */
public record ChatSessionDetailResponse(ChatSessionResponse session, List<ChatMessageResponse> messages,
                                        boolean running) {

    public ChatSessionDetailResponse withRunning(boolean running) {
        return new ChatSessionDetailResponse(session, messages, running);
    }
}
