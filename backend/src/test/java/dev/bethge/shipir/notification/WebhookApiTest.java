package dev.bethge.shipir.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class WebhookApiTest extends WebhookTestBase {

    private static final String SECRET_URL = "http://hooks.example.com:8081/services/T000/SECRET-TOKEN-123?key=abc";

    private String create(Login l, String name, String url) throws Exception {
        MvcResult r = mvc.perform(l.write(post("/api/notifications/webhooks")).contentType(APPLICATION_JSON)
                .content(createJson(name, url, "[\"HOST_DOWN\"]", true))).andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void createReturnsHintOnlyAndStoresCiphertext(CapturedOutput output) throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(post("/api/notifications/webhooks")).contentType(APPLICATION_JSON)
                        .content(createJson("Slack", SECRET_URL, "[\"HOST_DOWN\",\"HOST_UP\"]", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Slack"))
                .andExpect(jsonPath("$.urlHint").value("http://hooks.example.com:8081"))
                .andExpect(jsonPath("$.url").doesNotExist())
                .andExpect(jsonPath("$.eventTypes.length()").value(2))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.lastDeliveryAt").doesNotExist());
        mvc.perform(get("/api/notifications/webhooks").session(l.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].urlHint").value("http://hooks.example.com:8081"));
        byte[] stored = jdbc.queryForObject("SELECT url_encrypted FROM notification_webhooks", byte[].class);
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("SECRET-TOKEN");
        assertThat(jdbc.queryForObject("SELECT url_hint FROM notification_webhooks", String.class))
                .doesNotContain("SECRET").doesNotContain("/services");
        assertThat(output.getAll()).doesNotContain("SECRET-TOKEN-123");
    }

    @Test
    void listResponseNeverContainsFullUrl() throws Exception {
        Login l = login("admin");
        create(l, "Slack", SECRET_URL);
        String body = mvc.perform(get("/api/notifications/webhooks").session(l.session()))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SECRET-TOKEN").doesNotContain("services");
    }

    @Test
    void rejectsInvalidUrls() throws Exception {
        Login l = login("admin");
        for (String bad : List.of("file:///etc/passwd", "ftp://example.com/x", "/relative/path", "example.com/hook",
                "http://", "https:///nohost", "http://" + "a".repeat(2100))) {
            mvc.perform(l.write(post("/api/notifications/webhooks")).contentType(APPLICATION_JSON)
                            .content(createJson("Bad", bad, "[\"HOST_UP\"]", true)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_webhooks", Integer.class)).isZero();
    }

    @Test
    void rejectsEmptyEventTypesAndBlankName() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(post("/api/notifications/webhooks")).contentType(APPLICATION_JSON)
                .content(createJson("X", "http://a.example", "[]", true))).andExpect(status().isBadRequest());
        mvc.perform(l.write(post("/api/notifications/webhooks")).contentType(APPLICATION_JSON)
                .content(createJson(" ", "http://a.example", "[\"HOST_UP\"]", true))).andExpect(status().isBadRequest());
    }

    @Test
    void duplicateNameIs409() throws Exception {
        Login l = login("admin");
        create(l, "Slack", "http://a.example/x");
        mvc.perform(l.write(post("/api/notifications/webhooks")).contentType(APPLICATION_JSON)
                        .content(createJson("Slack", "http://b.example/y", "[\"HOST_UP\"]", true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_NAME_TAKEN"));
    }

    @Test
    void updateWithoutUrlKeepsStoredUrlAndWithUrlReplacesIt() throws Exception {
        Login l = login("admin");
        String id = create(l, "Slack", "http://a.example/old");
        byte[] before = jdbc.queryForObject("SELECT url_encrypted FROM notification_webhooks", byte[].class);
        mvc.perform(l.write(put("/api/notifications/webhooks/" + id)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"eventTypes\":[\"HOST_UP\"],\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.urlHint").value("http://a.example"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.eventTypes[0]").value("HOST_UP"));
        assertThat(jdbc.queryForObject("SELECT url_encrypted FROM notification_webhooks", byte[].class))
                .isEqualTo(before);
        mvc.perform(l.write(put("/api/notifications/webhooks/" + id)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"url\":\"https://b.example:9/new\","
                                + "\"eventTypes\":[\"HOST_UP\"],\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urlHint").value("https://b.example:9"));
    }

    @Test
    void updateToTakenNameIs409AndUnknownIdIs404() throws Exception {
        Login l = login("admin");
        create(l, "One", "http://a.example/1");
        String two = create(l, "Two", "http://a.example/2");
        mvc.perform(l.write(put("/api/notifications/webhooks/" + two)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"One\",\"eventTypes\":[\"HOST_UP\"],\"enabled\":true}"))
                .andExpect(status().isConflict());
        mvc.perform(l.write(put("/api/notifications/webhooks/" + java.util.UUID.randomUUID()))
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"N\",\"eventTypes\":[\"HOST_UP\"],\"enabled\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(l.write(delete("/api/notifications/webhooks/" + java.util.UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesWebhook() throws Exception {
        Login l = login("admin");
        String id = create(l, "Slack", "http://a.example/x");
        mvc.perform(l.write(delete("/api/notifications/webhooks/" + id))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_webhooks", Integer.class)).isZero();
    }

    @Test
    void operatorAndViewerAreForbidden() throws Exception {
        Login admin = login("admin");
        String id = create(admin, "Slack", "http://a.example/x");
        for (String user : List.of("operator", "viewer")) {
            Login l = login(user);
            mvc.perform(get("/api/notifications/webhooks").session(l.session())).andExpect(status().isForbidden());
            mvc.perform(l.write(post("/api/notifications/webhooks")).contentType(APPLICATION_JSON)
                    .content(createJson("Z", "http://a.example", "[\"HOST_UP\"]", true))).andExpect(status().isForbidden());
            mvc.perform(l.write(put("/api/notifications/webhooks/" + id)).contentType(APPLICATION_JSON)
                    .content("{\"name\":\"N\",\"eventTypes\":[\"HOST_UP\"],\"enabled\":true}")).andExpect(status().isForbidden());
            mvc.perform(l.write(delete("/api/notifications/webhooks/" + id))).andExpect(status().isForbidden());
            mvc.perform(l.write(post("/api/notifications/webhooks/" + id + "/test"))).andExpect(status().isForbidden());
        }
    }

    @Test
    void writesWithoutCsrfTokenAreForbidden() throws Exception {
        Login l = login("admin");
        mvc.perform(post("/api/notifications/webhooks").session(l.session()).contentType(APPLICATION_JSON)
                .content(createJson("Z", "http://a.example", "[\"HOST_UP\"]", true))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_webhooks", Integer.class)).isZero();
    }

    @Test
    void createUpdateDeleteAreAudited() throws Exception {
        Login l = login("admin");
        jdbc.update("DELETE FROM audit_log");
        String id = create(l, "Slack", SECRET_URL);
        mvc.perform(l.write(put("/api/notifications/webhooks/" + id)).contentType(APPLICATION_JSON)
                .content("{\"name\":\"S2\",\"eventTypes\":[\"HOST_UP\"],\"enabled\":true}")).andExpect(status().isOk());
        mvc.perform(l.write(delete("/api/notifications/webhooks/" + id))).andExpect(status().isNoContent());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM audit_log ORDER BY occurred_at");
        assertThat(rows).extracting(r -> r.get("action"))
                .containsExactly("WEBHOOK_CREATE", "WEBHOOK_UPDATE", "WEBHOOK_DELETE");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("username")).isEqualTo("admin");
            assertThat(r.get("target_id")).isEqualTo(id);
        });
        assertThat(rows.toString()).doesNotContain("SECRET-TOKEN");
    }
}
