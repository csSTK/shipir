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
}
