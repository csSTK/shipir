package dev.bethge.shipir.common.security;

import java.util.UUID;

/**
 * Published when a host was deleted. Long-lived channels bound to it (exec consoles, log and event subscriptions)
 * end, as nothing re-authorizes them against a host that no longer exists. Handled after commit.
 */
public record HostRemovedEvent(UUID hostId) {
}
