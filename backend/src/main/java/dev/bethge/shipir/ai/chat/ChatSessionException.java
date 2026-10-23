package dev.bethge.shipir.ai.chat;

import org.springframework.http.HttpStatus;

/** A chat session request that cannot be fulfilled; carries the HTTP status and a stable error code. */
public class ChatSessionException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    ChatSessionException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static ChatSessionException notFound() {
        return new ChatSessionException(HttpStatus.NOT_FOUND, "CHAT_SESSION_NOT_FOUND", "Chat session not found");
    }

    HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
