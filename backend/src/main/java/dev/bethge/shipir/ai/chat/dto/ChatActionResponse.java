package dev.bethge.shipir.ai.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/** Result of confirming or rejecting an action; {@code runId} is set when the assistant resumed. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatActionResponse(UUID messageId, String status, boolean error, String result, UUID runId) {
}
