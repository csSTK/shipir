package dev.bethge.shipir.notification;

import dev.bethge.shipir.compose.DeploymentRunFailedEvent;
import dev.bethge.shipir.compose.GitAutoUpdateFailedEvent;
import dev.bethge.shipir.compose.ImageAutoUpdateEvent;
import dev.bethge.shipir.host.ContainerProblemEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Listener side of DEPLOYMENT_FAILED, CONTAINER_CRASHED, CONTAINER_UNHEALTHY and GIT_AUTO_UPDATE_FAILED. */
class NotificationNewTypesTest extends NotificationTestBase {

    @Autowired
    ApplicationEventPublisher publisher;

    private UUID deployment;
    private UUID otherDeployment;

    @BeforeEach
    void deployments() {
        deployment = insertDeployment("web");
        otherDeployment = insertDeployment("db");
    }

    private UUID insertDeployment(String name) {
        return jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT ?, ?, 'x', id FROM users WHERE username = 'admin' RETURNING id", UUID.class, name, visibleHost);
    }

    private void deploymentFailed(UUID deploymentId) {
        publisher.publishEvent(new DeploymentRunFailedEvent(visibleHost, "notif-host", deploymentId, "web", "UP", 1,
                false, null, Instant.parse("2026-10-24T10:00:00Z")));
    }

    private List<String> recipients(String type) {
        return jdbc.queryForList("SELECT u.username FROM notifications n JOIN users u ON u.id = n.user_id "
                + "WHERE n.type = ? ORDER BY u.username", String.class, type);
    }

    @Test
    void deploymentFailedGoesToEveryoneWhoCanViewTheDeployment() {
        deploymentFailed(deployment);
        assertThat(recipients("DEPLOYMENT_FAILED")).containsExactly("admin", "operator", "viewer");
        assertThat(jdbc.queryForMap("SELECT host_id, host_name, deployment_id, deployment_name, container_id, message "
                + "FROM notifications WHERE type = 'DEPLOYMENT_FAILED' LIMIT 1"))
                .containsEntry("host_id", visibleHost).containsEntry("deployment_id", deployment)
                .containsEntry("deployment_name", "web").containsEntry("container_id", null)
                .containsEntry("message", "Deployment \"web\" on \"notif-host\" failed (step UP, exit code 1)");
    }

    private String imageUpdateFailedMessage(String errorCode) {
        jdbc.update("DELETE FROM notifications");
        publisher.publishEvent(new ImageAutoUpdateEvent(visibleHost, "notif-host", deployment, "web", false, errorCode,
                Instant.parse("2026-10-24T10:00:00Z")));
        return jdbc.queryForObject("SELECT message FROM notifications WHERE type = 'IMAGE_UPDATE_FAILED' LIMIT 1",
                String.class);
    }

    @Test
    void imageUpdateFailedMessageIsReadableAndHasNoRawReasonCode() {
        assertThat(imageUpdateFailedMessage("UNHEALTHY_ROLLED_BACK")).isEqualTo(
                "Image update of deployment \"web\" on \"notif-host\" failed: the updated containers did not become "
                        + "healthy. The previous images were restored.");
        assertThat(imageUpdateFailedMessage("UP_FAILED_NOT_ROLLED_BACK")).isEqualTo(
                "Image update of deployment \"web\" on \"notif-host\" failed: the updated containers could not be "
                        + "started. The previous images could not be restored.");
        assertThat(imageUpdateFailedMessage("PULL_FAILED")).isEqualTo(
                "Image update of deployment \"web\" on \"notif-host\" failed: the new images could not be pulled. "
                        + "Nothing was changed.");
        assertThat(imageUpdateFailedMessage("TIMEOUT_NOT_ROLLED_BACK")).doesNotContain("TIMEOUT_NOT_ROLLED_BACK");
        assertThat(imageUpdateFailedMessage("SOMETHING_NEW")).doesNotContain("SOMETHING_NEW");
        // the raw code stays available for webhook consumers
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'IMAGE_UPDATE_FAILED'",
                Integer.class)).isPositive();
    }

    @Test
    void userWithoutAnyGrantGetsNothing() {
        jdbc.update("DELETE FROM permissions WHERE user_id = ?", userId("viewer"));
        deploymentFailed(deployment);
        assertThat(recipients("DEPLOYMENT_FAILED")).containsExactly("admin", "operator");
    }

    @Test
    void deploymentGrantCoversOnlyThatDeployment() {
        jdbc.update("DELETE FROM permissions WHERE user_id = ?", userId("viewer"));
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) VALUES (?, ?, 'VIEW')",
                userId("viewer"), deployment);
        deploymentFailed(deployment);
        assertThat(recipients("DEPLOYMENT_FAILED")).contains("viewer");
        jdbc.update("DELETE FROM notifications");
        deploymentFailed(otherDeployment);
        assertThat(recipients("DEPLOYMENT_FAILED")).doesNotContain("viewer");
    }

    @Test
    void optedOutAndDisabledUsersAreSkipped() {
        jdbc.update("INSERT INTO notification_preferences (user_id, type, in_app_enabled) VALUES (?, 'DEPLOYMENT_FAILED', false)",
                userId("operator"));
        jdbc.update("UPDATE users SET disabled_at = now() WHERE username = 'viewer'");
        try {
            deploymentFailed(deployment);
            assertThat(recipients("DEPLOYMENT_FAILED")).containsExactly("admin");
        } finally {
            jdbc.update("UPDATE users SET disabled_at = NULL WHERE username = 'viewer'");
        }
    }

    @Test
    void containerCrashStoresContainerAndDeploymentContext() {
        publisher.publishEvent(new ContainerProblemEvent(ContainerProblemEvent.Kind.CRASHED, visibleHost, "notif-host",
                "abc123", "web-1", deployment, "web", 137, Instant.parse("2026-10-24T10:00:00Z")));
        assertThat(recipients("CONTAINER_CRASHED")).containsExactly("admin", "operator", "viewer");
        assertThat(jdbc.queryForMap("SELECT container_id, container_name, deployment_name, message FROM notifications "
                + "WHERE type = 'CONTAINER_CRASHED' LIMIT 1"))
                .containsEntry("container_id", "abc123").containsEntry("container_name", "web-1")
                .containsEntry("deployment_name", "web")
                .containsEntry("message", "Container \"web-1\" on \"notif-host\" crashed (exit code 137)");
    }

    @Test
    void containerOutsideManagedDeploymentNeedsAHostGrant() {
        UUID otherHost = insertHost("other-host");
        publisher.publishEvent(new ContainerProblemEvent(ContainerProblemEvent.Kind.UNHEALTHY, otherHost, "other-host",
                "abc", "cache", null, null, null, Instant.now()));
        assertThat(recipients("CONTAINER_UNHEALTHY")).containsExactly("admin");

        jdbc.update("DELETE FROM permissions WHERE user_id = ?", userId("viewer"));
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) VALUES (?, ?, 'VIEW')",
                userId("viewer"), deployment);
        jdbc.update("DELETE FROM notifications");
        publisher.publishEvent(new ContainerProblemEvent(ContainerProblemEvent.Kind.UNHEALTHY, visibleHost, "notif-host",
                "abc", "cache", null, null, null, Instant.now()));
        assertThat(recipients("CONTAINER_UNHEALTHY")).containsExactly("admin", "operator");
    }

    @Test
    void gitAutoUpdateFailureNotifiesWithErrorCode() {
        publisher.publishEvent(new GitAutoUpdateFailedEvent(visibleHost, "notif-host", deployment, "web",
                "GIT_AUTH_FAILED", Instant.now()));
        assertThat(recipients("GIT_AUTO_UPDATE_FAILED")).containsExactly("admin", "operator", "viewer");
        assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE type = 'GIT_AUTO_UPDATE_FAILED' LIMIT 1",
                String.class)).contains("GIT_AUTH_FAILED").contains("web");
    }
}
