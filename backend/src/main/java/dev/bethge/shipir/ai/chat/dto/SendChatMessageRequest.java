package dev.bethge.shipir.ai.chat.dto;

import dev.bethge.shipir.ai.chat.ViewContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code context} is optional: the page the user is looking at (see {@link ViewContext}). */
public record SendChatMessageRequest(@NotBlank @Size(max = 8000) String content, @Valid ViewContext context) {
}
