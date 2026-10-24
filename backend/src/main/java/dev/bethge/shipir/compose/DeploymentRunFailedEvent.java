package dev.bethge.shipir.compose;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the compose run of a deployment (deploy, rollback, redeploy, pull &amp; update, auto-update) ended in
 * failure. {@code failedStep} is the compose command, {@code error} a code such as COMPOSE_UNAVAILABLE; either may be null.
 */
public record DeploymentRunFailedEvent(UUID hostId, String hostName, UUID deploymentId, String deploymentName,
                                       String failedStep, Integer exitCode, boolean timedOut, String error,
                                       Instant occurredAt) {
}
