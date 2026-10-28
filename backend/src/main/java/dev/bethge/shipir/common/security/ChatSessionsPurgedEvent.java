package dev.bethge.shipir.common.security;

import java.util.Collection;
import java.util.UUID;

/**
 * Published when the retention purge deleted chat sessions. In-memory state kept per session (e.g. the view context
 * of a paused chat run) is dropped. Handled after commit.
 */
public record ChatSessionsPurgedEvent(Collection<UUID> sessionIds) {
}
