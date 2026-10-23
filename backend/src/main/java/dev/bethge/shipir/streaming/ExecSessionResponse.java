package dev.bethge.shipir.streaming;

/** Created console: subscribe to {@code output}, SEND raw bytes to {@code input} and JSON {@code {cols,rows}} to {@code resize}. */
public record ExecSessionResponse(String sessionId, String output, String input, String resize) {
}
