package dev.bethge.shipir.common.security;

import java.util.UUID;

/**
 * Published after a managed deployment was deleted. Its grants disappear with it (ON DELETE CASCADE) without a
 * {@link UserAccessChangedEvent}, so consoles that were allowed only through a grant on it must end.
 */
public record DeploymentRemovedEvent(UUID deploymentId) {
}
