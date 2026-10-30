package dev.bethge.shipir.common.exception;

/**
 * Marker for exceptions that refuse a request because a throttle is locked (HTTP 429). {@code AuditAspect} writes no
 * audit row for them, or a flood of blocked requests would become a flood of DB writes; the attempt that starts a
 * lockout is a normal failure and is audited as such.
 */
public interface RequestThrottled {
}
