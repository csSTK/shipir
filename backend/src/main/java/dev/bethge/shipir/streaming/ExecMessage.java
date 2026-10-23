package dev.bethge.shipir.streaming;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Frame of {@code /topic/exec/{sessionId}}. OUTPUT carries terminal bytes as Base64 in {@code data} (bytes can split
 * a UTF-8 character, so they are not sent as text). STATUS is the last frame: ENDED with a {@code reason}
 * (EXITED, IDLE_TIMEOUT, LOGOUT, DISCONNECTED, CLOSED) or ERROR with a stable {@code reason} code.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecMessage(long seq, Type type, String data, Status status, String reason) {

    public enum Type { OUTPUT, STATUS }

    public enum Status { ENDED, ERROR }
}
