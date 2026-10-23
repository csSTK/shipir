package dev.bethge.shipir.ai.chat;

/** Only set on pending actions that wait for the user's confirmation. */
public enum ChatMessageStatus {
    PENDING, CONFIRMED, REJECTED
}
