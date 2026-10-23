package dev.bethge.shipir.ai.chat.dto;

import java.util.List;

public record ChatSessionDetailResponse(ChatSessionResponse session, List<ChatMessageResponse> messages) {
}
