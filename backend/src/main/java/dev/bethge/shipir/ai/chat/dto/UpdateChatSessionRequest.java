package dev.bethge.shipir.ai.chat.dto;

import jakarta.validation.constraints.Size;

/** Both fields optional; {@code null} leaves the value unchanged. */
public record UpdateChatSessionRequest(@Size(max = 255) String title, Boolean sharedWithAdmin) {
}
