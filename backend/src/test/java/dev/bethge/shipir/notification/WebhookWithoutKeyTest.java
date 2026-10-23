package dev.bethge.shipir.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "shipir.encryption-key=")
class WebhookWithoutKeyTest extends NotificationTestBase {

    @BeforeEach
    void host() {
        jdbc.update("DELETE FROM notification_webhooks");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Webhook host', 'LOCAL', id FROM users WHERE username = 'admin'");
    }

    @Test
    void createWithoutEncryptionKeyIsRejected() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(post("/api/notifications/webhooks")).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"url\":\"http://a.example/x\",\"eventTypes\":[\"HOST_UP\"],\"enabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENCRYPTION_KEY_MISSING"));
        mvc.perform(get("/api/notifications/webhooks").session(l.session())).andExpect(status().isOk());
    }
}
