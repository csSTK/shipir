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
    BUILTIN_NETWORK,
    /** Container lifecycle action does not fit the current state (daemon 304 or 409): already started/stopped, not running, (not) paused. */
    INVALID_STATE,
    /** The registry rejected a pull (denied, unauthorized, no such tag) or could not be reached; the daemon's text is never passed on. */
    PULL_FAILED,
    /** Create: a resource with that name already exists. */
    NAME_TAKEN,
    /** Create + start: the container was created but could not start (e.g. port taken); it has been removed again. */
    START_FAILED,
    /** The container path is not an absolute path, contains NUL, or is not a directory where one is needed. */
    INVALID_PATH,
    /** A file transfer or directory scan exceeded the allowed number of bytes; aborted while streaming. */
    SIZE_LIMIT_EXCEEDED
}
