package dev.bethge.shipir.ai.chat.dto;

import java.util.List;

public record ChatSessionPageResponse(List<ChatSessionResponse> items, int page, int size, long totalElements) {
}
