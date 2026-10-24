package dev.bethge.shipir.retention;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RetentionApiTest extends RetentionTestBase {

    private static String body(Object audit, Object notif) {
        return "{\"auditLogDays\":%s,\"notificationDays\":%s}".formatted(audit, notif);
    }

    @Test
    void adminGetsDefaultsAndRoundTrips() throws Exception {
        Login l = login("admin");
        mvc.perform(get("/api/settings/retention").session(l.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditLogDays").value(365))
                .andExpect(jsonPath("$.notificationDays").value(30));
        mvc.perform(l.write(put("/api/settings/retention")).contentType(APPLICATION_JSON).content(body(90, 7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditLogDays").value(90))
                .andExpect(jsonPath("$.notificationDays").value(7))
                .andExpect(jsonPath("$.updatedAt").exists());
        mvc.perform(get("/api/settings/retention").session(l.session()))
                .andExpect(jsonPath("$.auditLogDays").value(90))
                .andExpect(jsonPath("$.notificationDays").value(7));
        assertThat(jdbc.queryForObject("SELECT u.username FROM retention_settings r JOIN users u "
                + "ON u.id = r.updated_by", String.class)).isEqualTo("admin");
    }

    @Test
    void acceptsBounds() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/retention")).contentType(APPLICATION_JSON).content(body(1, 3650)))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsInvalidValuesWithErrorResponse() throws Exception {
        Login l = login("admin");
        for (String bad : new String[]{body(0, 30), body(3651, 30), body(30, -1), body(30, 3651),
                body("null", 30), "{\"auditLogDays\":30}", "{}", body("\"x\"", 30)}) {
            mvc.perform(l.write(put("/api/settings/retention")).contentType(APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").exists())
                    .andExpect(jsonPath("$.message").exists());
        }
        assertThat(jdbc.queryForObject("SELECT audit_log_days FROM retention_settings", Integer.class)).isEqualTo(365);
    }

    @Test
    void chatSessionDaysDefaultsToNinetyAndCanBeChanged() throws Exception {
        Login l = login("admin");
        mvc.perform(get("/api/settings/retention").session(l.session()))
                .andExpect(jsonPath("$.chatSessionDays").value(90));
        mvc.perform(l.write(put("/api/settings/retention")).contentType(APPLICATION_JSON)
                        .content("{\"auditLogDays\":90,\"notificationDays\":7,\"chatSessionDays\":14}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatSessionDays").value(14));
        assertThat(jdbc.queryForObject("SELECT chat_session_days FROM retention_settings", Integer.class))
                .isEqualTo(14);
    }

    @Test
    void omittedChatSessionDaysKeepsCurrentValue() throws Exception {
        jdbc.update("UPDATE retention_settings SET chat_session_days = 21");
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/retention")).contentType(APPLICATION_JSON).content(body(90, 7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatSessionDays").value(21));
    }

    @Test
    void rejectsChatSessionDaysOutOfRange() throws Exception {
        Login l = login("admin");
        for (String bad : new String[]{"0", "3651", "\"x\""}) {
            mvc.perform(l.write(put("/api/settings/retention")).contentType(APPLICATION_JSON)
                            .content("{\"auditLogDays\":90,\"notificationDays\":7,\"chatSessionDays\":" + bad + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").exists());
        }
        assertThat(jdbc.queryForObject("SELECT chat_session_days FROM retention_settings", Integer.class))
                .isEqualTo(90);
    }

    @Test
    void operatorAndViewerAreForbidden() throws Exception {
        for (String user : new String[]{"operator", "viewer"}) {
            Login l = login(user);
            mvc.perform(get("/api/settings/retention").session(l.session())).andExpect(status().isForbidden());
            mvc.perform(l.write(put("/api/settings/retention")).contentType(APPLICATION_JSON).content(body(10, 10)))
                    .andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT audit_log_days FROM retention_settings", Integer.class)).isEqualTo(365);
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/api/settings/retention")).andExpect(status().isUnauthorized());
    }

    @Test
    void putWithoutCsrfIsForbidden() throws Exception {
        Login l = login("admin");
        mvc.perform(put("/api/settings/retention").session(l.session()).contentType(APPLICATION_JSON)
                .content(body(10, 10))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT audit_log_days FROM retention_settings", Integer.class)).isEqualTo(365);
    }

    @Test
    void putCreatesAuditEntry() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/retention")).contentType(APPLICATION_JSON).content(body(90, 7)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'RETENTION_UPDATE' "
                + "AND username = 'admin' AND outcome = 'SUCCESS' AND target_type = 'RETENTION_SETTINGS'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void putRecordsOldAndNewValuesInAuditDetails() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(put("/api/settings/retention")).contentType(APPLICATION_JSON)
                .content("{\"auditLogDays\":100,\"notificationDays\":20,\"chatSessionDays\":30}"))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM audit_log WHERE action = 'RETENTION_UPDATE'");
        mvc.perform(l.write(put("/api/settings/retention")).contentType(APPLICATION_JSON).content(body(90, 7)))
                .andExpect(status().isOk());

        String details = jdbc.queryForObject(
                "SELECT details::text FROM audit_log WHERE action = 'RETENTION_UPDATE'", String.class);
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(details);
        assertThat(json.at("/old/auditLogDays").asInt()).isEqualTo(100);
        assertThat(json.at("/old/notificationDays").asInt()).isEqualTo(20);
        assertThat(json.at("/old/chatSessionDays").asInt()).isEqualTo(30);
        assertThat(json.at("/new/auditLogDays").asInt()).isEqualTo(90);
        assertThat(json.at("/new/notificationDays").asInt()).isEqualTo(7);
        assertThat(json.at("/new/chatSessionDays").asInt()).isEqualTo(30);
        assertThat(json.has("via")).isFalse();
    }
}
