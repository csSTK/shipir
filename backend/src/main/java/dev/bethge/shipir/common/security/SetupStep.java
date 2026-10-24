package dev.bethge.shipir.common.security;

/** First-start setup steps, in order. A user without a pending step is fully set up. */
public enum SetupStep {
    /** Choose an own password (initial admin password must be replaced). */
    PASSWORD,
    /** Admins only, while the security setting requires it: enrol TOTP two-factor authentication. */
    TWO_FACTOR,
    /** Admins only: add the first Docker environment while none exists. */
    ENVIRONMENT
}
