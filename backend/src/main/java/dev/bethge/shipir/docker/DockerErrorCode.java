package dev.bethge.shipir.docker;

/** Stable reasons a Docker connection can fail. */
public enum DockerErrorCode {
    UNREACHABLE,
    TIMEOUT,
    TLS_ERROR,
    PERMISSION_DENIED,
    /** The supplied CA, client certificate or key is not usable PEM: a validation problem, not a connection one. */
    INVALID_TLS_MATERIAL
}
