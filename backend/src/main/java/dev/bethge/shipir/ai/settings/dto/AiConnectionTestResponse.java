package dev.bethge.shipir.ai.settings.dto;

/** {@code status}: OK, AUTH_FAILED or UNREACHABLE. */
public record AiConnectionTestResponse(String status) {
}
