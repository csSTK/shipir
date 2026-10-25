package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CRUD of {@code /managed-deployments/{id}/uptime-checks}: validation, limit, permissions, CSRF, audit, notification types. */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class UptimeCheckApiTest extends DeploymentTestBase {

    private String deploymentId;

    @BeforeEach
    void createDeployment() throws Exception {
        jdbc.update("DELETE FROM notifications");
        Login admin = login("admin");
        String body = mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        deploymentId = JsonPath.read(body, "$.id");
    }

    private String checks() {
        return url() + "/" + deploymentId + "/uptime-checks";
    }

    private static String json(String name, String url, Integer expected, int interval, int timeout) {
        return "{\"name\":\"%s\",\"url\":\"%s\",\"expectedStatus\":%s,\"intervalSeconds\":%d,\"timeoutSeconds\":%d}"
                .formatted(name, url, expected, interval, timeout);
    }

    private String create(Login l, String name, String url) throws Exception {
        String body = mvc.perform(l.write(post(checks())).contentType(APPLICATION_JSON)
                        .content(json(name, url, null, 60, 5)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void operatorCreatesListsUpdatesAndDeletes() throws Exception {
        Login op = login("operator");
        String id = create(op, "home", "https://example.com/health");

        mvc.perform(get(checks()).session(op.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("home"))
                .andExpect(jsonPath("$[0].lastStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].recentResults.length()").value(0));

        mvc.perform(op.write(put(checks() + "/" + id)).contentType(APPLICATION_JSON)
                        .content(json("renamed", "https://example.com/up", 204, 120, 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed"))
                .andExpect(jsonPath("$.expectedStatus").value(204))
                .andExpect(jsonPath("$.intervalSeconds").value(120));

        mvc.perform(op.write(delete(checks() + "/" + id))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM uptime_checks", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com/", "file:///etc/passwd", "https://user:pw@example.com/", "https:///path",
            "example.com", "javascript:alert(1)"})
    void rejectsNonHttpUrlsAndEmbeddedCredentials(String url) throws Exception {
        Login op = login("operator");
        mvc.perform(op.write(post(checks())).contentType(APPLICATION_JSON).content(json("bad", url, null, 60, 5)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM uptime_checks", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"29,5", "86401,5", "60,0", "60,31"})
    void rejectsOutOfRangeIntervalOrTimeout(String values) throws Exception {
        Login op = login("operator");
        String[] v = values.split(",");
        mvc.perform(op.write(post(checks())).contentType(APPLICATION_JSON)
                        .content(json("bad", "https://example.com/", null, Integer.parseInt(v[0]), Integer.parseInt(v[1]))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void allowsAtMostTenChecksPerDeployment() throws Exception {
        Login op = login("operator");
        for (int i = 0; i < 10; i++) {
            create(op, "c" + i, "https://example.com/" + i);
        }
        mvc.perform(op.write(post(checks())).contentType(APPLICATION_JSON)
                        .content(json("eleventh", "https://example.com/x", null, 60, 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPTIME_CHECK_LIMIT"));
    }

    @Test
    void viewerCannotReadOrWriteAndAnonymousIsUnauthorized() throws Exception {
        Login viewer = login("viewer");
        mvc.perform(get(checks()).session(viewer.session())).andExpect(status().isForbidden());
        mvc.perform(viewer.write(post(checks())).contentType(APPLICATION_JSON)
                        .content(json("x", "https://example.com/", null, 60, 5)))
                .andExpect(status().isForbidden());
        mvc.perform(get(checks())).andExpect(status().isUnauthorized());
    }

    @Test
    void writeWithoutCsrfTokenIsRejected() throws Exception {
        Login op = login("operator");
        mvc.perform(post(checks()).session(op.session()).contentType(APPLICATION_JSON)
                        .content(json("x", "https://example.com/", null, 60, 5)))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorWithoutPermissionOnTheHostCannotTouchTheChecks() throws Exception {
        jdbc.update("DELETE FROM permissions WHERE user_id = (SELECT id FROM users WHERE username = 'operator')");
        Login op = login("operator");
        mvc.perform(get(checks()).session(op.session())).andExpect(status().is4xxClientError());
        mvc.perform(op.write(post(checks())).contentType(APPLICATION_JSON)
                        .content(json("x", "https://example.com/", null, 60, 5)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void unknownCheckIdIsNotFound() throws Exception {
        Login op = login("operator");
        mvc.perform(op.write(delete(checks() + "/" + UUID.randomUUID()))).andExpect(status().isNotFound());
    }

    @Test
    void changesAreAuditedWithTheNameOnly() throws Exception {
        Login op = login("operator");
        String id = create(op, "home", "https://example.com/health?token=supersecret");
        mvc.perform(op.write(put(checks() + "/" + id)).contentType(APPLICATION_JSON)
                .content(json("home2", "https://example.com/health?token=supersecret", null, 60, 5))).andExpect(status().isOk());
        mvc.perform(op.write(delete(checks() + "/" + id))).andExpect(status().isNoContent());

        List<String> actions = jdbc.queryForList(
                "SELECT action FROM audit_log WHERE action LIKE 'UPTIME_CHECK_%' ORDER BY occurred_at, id", String.class);
        assertThat(actions).containsExactly("UPTIME_CHECK_CREATE", "UPTIME_CHECK_UPDATE", "UPTIME_CHECK_DELETE");
        String details = jdbc.queryForObject(
                "SELECT string_agg(coalesce(details::text, ''), ' ') FROM audit_log WHERE action LIKE 'UPTIME_CHECK_%'", String.class);
        assertThat(details).contains("home").doesNotContain("supersecret").doesNotContain("example.com");
    }

    @Test
    void uptimeNotificationTypesAreOfferedAsPreferences() throws Exception {
        Login admin = login("admin");
        mvc.perform(get("/api/notifications/preferences").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='UPTIME_DOWN')]").exists())
                .andExpect(jsonPath("$[?(@.type=='UPTIME_UP')]").exists());
    }
}
