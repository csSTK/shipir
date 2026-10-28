package dev.bethge.shipir.ai.chat.dto;

/** Write actions of the caller's chats still waiting for Confirm or Reject, across all sessions. */
public record PendingActionCountResponse(long count) {
}
