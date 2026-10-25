package dev.bethge.shipir.common.security;

/**
 * Published when something a user's live access depends on changed (role, disabled, deleted, password, grants,
 * IdP role). Long-lived channels such as WebSocket subscriptions and exec consoles re-check their authorization
 * against the current database state when they see it.
 */
public record UserAccessChangedEvent(String username) {
}
