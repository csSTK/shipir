package dev.bethge.shipir.notification;

import dev.bethge.shipir.compose.DeploymentRunFailedEvent;
import dev.bethge.shipir.compose.GitAutoUpdateFailedEvent;
import dev.bethge.shipir.compose.ImageAutoUpdateEvent;
import dev.bethge.shipir.compose.UptimeStateChangedEvent;
import dev.bethge.shipir.host.ContainerProblemEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * What an event of the new notification types means for notifications and webhooks: type, target (host, deployment,
 * container) and a message that contains no secrets (only names, step and exit/error codes). {@code deploymentId} and
 * the container fields are null where not applicable.
 */
record NotificationDraft(NotificationType type, UUID hostId, String hostName, UUID deploymentId, String deploymentName,
                         String containerId, String containerName, String message, Integer exitCode, String errorCode,
                         Instant occurredAt) {

    private static final int MAX_MESSAGE = 500;

    static NotificationDraft of(DeploymentRunFailedEvent e) {
        String reason = e.timedOut() ? "timed out"
                : e.error() != null ? e.error()
                : e.failedStep() == null ? "failed"
                : "step " + e.failedStep() + (e.exitCode() == null ? "" : ", exit code " + e.exitCode());
        return new NotificationDraft(NotificationType.DEPLOYMENT_FAILED, e.hostId(), e.hostName(), e.deploymentId(),
                e.deploymentName(), null, null,
                cap("Deployment \"%s\" on \"%s\" failed (%s)".formatted(e.deploymentName(), e.hostName(), reason)),
                e.exitCode(), e.error(), e.occurredAt());
    }

    static NotificationDraft of(GitAutoUpdateFailedEvent e) {
        return new NotificationDraft(NotificationType.GIT_AUTO_UPDATE_FAILED, e.hostId(), e.hostName(),
                e.deploymentId(), e.deploymentName(), null, null,
                cap("Auto-update of deployment \"%s\" on \"%s\" failed (%s)".formatted(e.deploymentName(),
                        e.hostName(), e.errorCode())),
                null, e.errorCode(), e.occurredAt());
    }

    static NotificationDraft of(ImageAutoUpdateEvent e) {
        String message = e.applied()
                ? "Images of deployment \"%s\" on \"%s\" were updated".formatted(e.deploymentName(), e.hostName())
                : "Image update of deployment \"%s\" on \"%s\" failed (%s)".formatted(e.deploymentName(),
                        e.hostName(), e.errorCode());
        return new NotificationDraft(e.applied() ? NotificationType.IMAGE_UPDATE_APPLIED
                : NotificationType.IMAGE_UPDATE_FAILED, e.hostId(), e.hostName(), e.deploymentId(), e.deploymentName(),
                null, null, cap(message), null, e.errorCode(), e.occurredAt());
    }

    static NotificationDraft of(UptimeStateChangedEvent e) {
        String message = e.down()
                ? "Uptime check \"%s\" of deployment \"%s\" on \"%s\" is down (%s)".formatted(e.checkName(),
                        e.deploymentName(), e.hostName(), e.errorCode())
                : "Uptime check \"%s\" of deployment \"%s\" on \"%s\" is back up".formatted(e.checkName(),
                        e.deploymentName(), e.hostName());
        return new NotificationDraft(e.down() ? NotificationType.UPTIME_DOWN : NotificationType.UPTIME_UP, e.hostId(),
                e.hostName(), e.deploymentId(), e.deploymentName(), null, null, cap(message), null, e.errorCode(),
                e.occurredAt());
    }

    static NotificationDraft of(ContainerProblemEvent e) {
        boolean crashed = e.kind() == ContainerProblemEvent.Kind.CRASHED;
        String message = crashed
                ? "Container \"%s\" on \"%s\" crashed (exit code %s)".formatted(e.containerName(), e.hostName(),
                        e.exitCode())
                : "Container \"%s\" on \"%s\" is unhealthy".formatted(e.containerName(), e.hostName());
        return new NotificationDraft(crashed ? NotificationType.CONTAINER_CRASHED : NotificationType.CONTAINER_UNHEALTHY,
                e.hostId(), e.hostName(), e.deploymentId(), e.deploymentName(), e.containerId(), e.containerName(),
                cap(message), e.exitCode(), null, e.occurredAt());
    }

    private static String cap(String message) {
        return message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE);
    }
}
