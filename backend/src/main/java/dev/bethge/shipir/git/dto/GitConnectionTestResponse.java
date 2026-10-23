package dev.bethge.shipir.git.dto;

/** {@code status} is OK, AUTH_FAILED, NOT_FOUND or UNREACHABLE. */
public record GitConnectionTestResponse(String status) {
}
