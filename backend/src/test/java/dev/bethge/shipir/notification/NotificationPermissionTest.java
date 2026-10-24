package dev.bethge.shipir.notification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Notifications about hosts the user cannot see are hidden from list, unread count and mark-read. */
class NotificationPermissionTest extends NotificationTestBase {

    @Test
    void hiddenHostNotificationsAreNotListedOrCounted() throws Exception {
        UUID hidden = insertHost("hidden");
        insertNotification("viewer", "HOST_DOWN", false, Instant.now(), hidden);
        insertNotification("viewer", "HOST_DOWN", false, Instant.now(), null);
        UUID shown = insertNotification("viewer", "HOST_UP", false, Instant.now());
        Login l = login("viewer");
        mvc.perform(get("/api/notifications").session(l.session()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(shown.toString()));
        mvc.perform(get("/api/notifications?unreadOnly=true").session(l.session()))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/notifications/unread-count").session(l.session()))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void markReadOnHiddenNotificationIs404() throws Exception {
        UUID hidden = insertHost("hidden");
        UUID n = insertNotification("operator", "HOST_DOWN", false, Instant.now(), hidden);
        Login l = login("operator");
        mvc.perform(l.write(post("/api/notifications/{id}/read", n))).andExpect(status().isNotFound());
    }

    @Test
    void grantingTheHostRevealsItsNotifications() throws Exception {
        UUID hidden = insertHost("hidden");
        insertNotification("operator", "HOST_DOWN", false, Instant.now(), hidden);
        Login l = login("operator");
        mvc.perform(get("/api/notifications").session(l.session())).andExpect(jsonPath("$.totalElements").value(0));
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, 'VIEW')", userId("operator"), hidden);
        mvc.perform(get("/api/notifications").session(l.session())).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void adminSeesEverythingIncludingDeletedHosts() throws Exception {
        UUID hidden = insertHost("hidden");
        insertNotification("admin", "HOST_DOWN", false, Instant.now(), hidden);
        insertNotification("admin", "HOST_DOWN", false, Instant.now(), null);
        mvc.perform(get("/api/notifications").session(login("admin").session()))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    private UUID insertDeployment(UUID hostId, String name) {
        return jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT ?, ?, ?, id FROM users WHERE username = 'admin' RETURNING id", UUID.class,
                name, hostId, name + "/compose.yaml");
    }

    private void insertScoped(String username, String type, UUID hostId, UUID deploymentId, boolean read) {
        jdbc.update("INSERT INTO notifications (id, user_id, type, host_id, host_name, deployment_id, deployment_name, "
                        + "message, created_at, read_at) VALUES (?, ?, ?, ?, 'h', ?, 'd', 'msg', now(), ?)",
                UUID.randomUUID(), userId(username), type, hostId, deploymentId, read ? java.sql.Timestamp.from(Instant.now()) : null);
    }

    @Test
    void deploymentOnlyGrantHidesOtherDeploymentsAndUnscopedContainers() throws Exception {
        UUID host = insertHost("shared");
        UUID mine = insertDeployment(host, "mine");
        UUID other = insertDeployment(host, "other");
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) VALUES (?, ?, 'VIEW')", userId("operator"), mine);
        insertScoped("operator", "DEPLOYMENT_FAILED", host, mine, false);
        insertScoped("operator", "DEPLOYMENT_FAILED", host, other, false);
        insertScoped("operator", "DEPLOYMENT_FAILED", host, null, false);
        insertScoped("operator", "CONTAINER_CRASHED", host, null, false);
        UUID hiddenId = jdbc.queryForObject("SELECT id FROM notifications WHERE deployment_id = ?", UUID.class, other);
        Login l = login("operator");
        mvc.perform(get("/api/notifications").session(l.session()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].deploymentId").value(mine.toString()));
        mvc.perform(get("/api/notifications?unreadOnly=true").session(l.session()))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/notifications/unread-count").session(l.session()))
                .andExpect(jsonPath("$.count").value(1));
        mvc.perform(l.write(post("/api/notifications/{id}/read", hiddenId))).andExpect(status().isNotFound());
    }

    @Test
    void hostGrantStillSeesAllNotificationsOfTheHost() throws Exception {
        UUID host = insertHost("shared");
        UUID d = insertDeployment(host, "d");
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, 'VIEW')", userId("operator"), host);
        insertScoped("operator", "DEPLOYMENT_FAILED", host, d, false);
        insertScoped("operator", "CONTAINER_CRASHED", host, null, false);
        mvc.perform(get("/api/notifications").session(login("operator").session()))
                .andExpect(jsonPath("$.totalElements").value(2));
    }
}
