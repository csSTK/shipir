package dev.bethge.shipir.notification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationApiTest extends NotificationTestBase {

    @Test
    void listsNewestFirstWithShape() throws Exception {
        Instant now = Instant.now();
        insertNotification("viewer", "HOST_UP", false, now.minus(2, ChronoUnit.HOURS));
        UUID newest = insertNotification("viewer", "HOST_DOWN", true, now);
        Login l = login("viewer");
        mvc.perform(get("/api/notifications").session(l.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(newest.toString()))
                .andExpect(jsonPath("$.content[0].type").value("HOST_DOWN"))
                .andExpect(jsonPath("$.content[0].hostName").value("h"))
                .andExpect(jsonPath("$.content[0].hostId").value(visibleHost.toString()))
                .andExpect(jsonPath("$.content[0].message").value("msg"))
                .andExpect(jsonPath("$.content[0].read").value(true))
                .andExpect(jsonPath("$.content[0].createdAt").exists())
                .andExpect(jsonPath("$.content[1].read").value(false));
    }

    @Test
    void unreadOnlyFilterAndPaging() throws Exception {
        Instant now = Instant.now();
        insertNotification("viewer", "HOST_UP", true, now);
        insertNotification("viewer", "HOST_UP", false, now.minusSeconds(1));
        insertNotification("viewer", "HOST_UP", false, now.minusSeconds(2));
        Login l = login("viewer");
        mvc.perform(get("/api/notifications?unreadOnly=true&page=0&size=1").session(l.session()))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void onlyOwnNotificationsAreListedAndCounted() throws Exception {
        insertNotification("admin", "HOST_UP", false, Instant.now());
        insertNotification("viewer", "HOST_UP", false, Instant.now());
        Login l = login("viewer");
        mvc.perform(get("/api/notifications").session(l.session()))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/notifications/unread-count").session(l.session()))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void markOneAndAllRead() throws Exception {
        UUID a = insertNotification("operator", "HOST_UP", false, Instant.now());
        insertNotification("operator", "HOST_DOWN", false, Instant.now());
        Login l = login("operator");
        mvc.perform(l.write(post("/api/notifications/" + a + "/read"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications/unread-count").session(l.session()))
                .andExpect(jsonPath("$.count").value(1));
        mvc.perform(l.write(post("/api/notifications/read-all"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications/unread-count").session(l.session()))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void readAllDoesNotTouchOtherUsers() throws Exception {
        insertNotification("admin", "HOST_UP", false, Instant.now());
        Login l = login("viewer");
        mvc.perform(l.write(post("/api/notifications/read-all"))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE read_at IS NULL", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void otherUsersNotificationIs404AndStaysUnread() throws Exception {
        UUID adminsId = insertNotification("admin", "HOST_UP", false, Instant.now());
        Login l = login("viewer");
        mvc.perform(l.write(post("/api/notifications/" + adminsId + "/read"))).andExpect(status().isNotFound());
        mvc.perform(l.write(post("/api/notifications/" + UUID.randomUUID() + "/read")))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE read_at IS NULL", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void writesRequireCsrf() throws Exception {
        UUID id = insertNotification("viewer", "HOST_UP", false, Instant.now());
        Login l = login("viewer");
        mvc.perform(post("/api/notifications/" + id + "/read").session(l.session()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/notifications/read-all").session(l.session())).andExpect(status().isForbidden());
        mvc.perform(put("/api/notifications/preferences").session(l.session())
                .contentType(APPLICATION_JSON).content("[]")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousGets401() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/preferences")).andExpect(status().isUnauthorized());
    }

    @Test
    void preferencesDefaultToEnabledAndRoundTrip() throws Exception {
        Login l = login("viewer");
        mvc.perform(get("/api/notifications/preferences").session(l.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[?(@.type=='CONTAINER_CRASHED')].inAppEnabled").value(true))
                .andExpect(jsonPath("$[?(@.type=='HOST_DOWN')].inAppEnabled").value(true))
                .andExpect(jsonPath("$[?(@.type=='HOST_UP')].inAppEnabled").value(true));
        mvc.perform(l.write(put("/api/notifications/preferences")).contentType(APPLICATION_JSON)
                        .content("[{\"type\":\"HOST_UP\",\"inAppEnabled\":false}]"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/notifications/preferences").session(l.session()))
                .andExpect(jsonPath("$[?(@.type=='HOST_UP')].inAppEnabled").value(false))
                .andExpect(jsonPath("$[?(@.type=='HOST_DOWN')].inAppEnabled").value(true));
        mvc.perform(l.write(put("/api/notifications/preferences")).contentType(APPLICATION_JSON)
                        .content("[{\"type\":\"HOST_UP\",\"inAppEnabled\":true}]"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/notifications/preferences").session(l.session()))
                .andExpect(jsonPath("$[?(@.type=='HOST_UP')].inAppEnabled").value(true));
    }

    @Test
    void unknownPreferenceTypeIs400() throws Exception {
        Login l = login("viewer");
        mvc.perform(l.write(put("/api/notifications/preferences")).contentType(APPLICATION_JSON)
                        .content("[{\"type\":\"NOPE\",\"inAppEnabled\":false}]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setupGateBlocksWhileSetupPending() throws Exception {
        Login l = login("viewer");
        jdbc.update("UPDATE users SET password_changed_at = NULL WHERE username = 'viewer'");
        mvc.perform(get("/api/notifications").session(l.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SETUP_REQUIRED"));
        mvc.perform(get("/api/notifications/unread-count").session(l.session()))
                .andExpect(status().isForbidden());
    }
}
