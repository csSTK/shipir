package dev.bethge.shipir.ai.chat.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record ChatMessageResponse(UUID id, int seq, String role, String content, JsonNode toolCalls,
                                  String toolCallId, String status, Instant createdAt) {
}
