package dev.bethge.shipir.docker;

/** Stable reasons a Docker connection can fail. */
public enum DockerErrorCode {
    UNREACHABLE,
    TIMEOUT,
    TLS_ERROR,
    PERMISSION_DENIED
}
