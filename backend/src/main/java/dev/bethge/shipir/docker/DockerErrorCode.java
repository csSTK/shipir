package dev.bethge.shipir.docker;

/** Stable reasons a Docker connection can fail. */
public enum DockerErrorCode {
    UNREACHABLE,
    TIMEOUT,
    TLS_ERROR,
    PERMISSION_DENIED,
    /** The supplied CA, client certificate or key is not usable PEM: a validation problem, not a connection one. */
    INVALID_TLS_MATERIAL,
    /** The daemon answered 404: the container, image, volume or network does not exist (any more). */
    NOT_FOUND,
    /** The daemon answered 409: the resource is running or still used by a container. */
    IN_USE,
    /** Refused before calling Docker: bridge, host and none cannot be removed. */
    BUILTIN_NETWORK
}
