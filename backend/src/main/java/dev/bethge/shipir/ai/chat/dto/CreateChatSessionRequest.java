package dev.bethge.shipir.ai.chat.dto;

import jakarta.validation.constraints.Size;

/** Title is optional; a default is used when missing or blank. */
public record CreateChatSessionRequest(@Size(max = 255) String title) {
}
