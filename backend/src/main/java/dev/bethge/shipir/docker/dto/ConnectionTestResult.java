package dev.bethge.shipir.docker.dto;

import dev.bethge.shipir.docker.DockerErrorCode;

/** Outcome of a connection test; {@code errorCode} and {@code message} are only set when {@code ok} is false. */
public record ConnectionTestResult(boolean ok, String dockerVersion, DockerErrorCode errorCode, String message) {
}
