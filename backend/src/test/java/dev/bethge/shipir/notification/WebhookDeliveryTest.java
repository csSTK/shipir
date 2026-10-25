package dev.bethge.shipir.notification;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.bethge.shipir.host.HostStatusChangedEvent;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebhookDeliveryTest extends WebhookTestBase {

    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    ObjectMapper mapper;

    private String add(Login l, String name, String url, String events, boolean enabled) throws Exception {
        String body = mvc.perform(l.write(post("/api/notifications/webhooks")).contentType(APPLICATION_JSON)
                        .content(createJson(name, url, events, enabled)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asText();
    }

    private void down() {
        publisher.publishEvent(new HostStatusChangedEvent(jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class), "prod", Status.UP, Status.DOWN,
                "UNREACHABLE", Instant.parse("2026-10-23T10:00:00Z")));
    }

    private UUID insertDeployment(UUID host) {
        return jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'web', ?, 'x', id FROM users WHERE username = 'admin' RETURNING id", UUID.class, host);
    }

    private String lastStatus(String id) {
        return jdbc.queryForObject("SELECT last_delivery_status FROM notification_webhooks WHERE id = ?::uuid",
                String.class, id);
    }

    @Test
    void eventPostsExactlyOneGenericJsonPayload() throws Exception {
        Stub s = stub(200, 0);
        Login l = login("admin");
        String id = add(l, "A", s.url("/hook?x=1"), "[\"HOST_DOWN\"]", true);
        down();
        await().atMost(5, SECONDS).until(() -> s.bodies.size() == 1);
        Thread.sleep(300);
        assertThat(s.bodies).hasSize(1);
        assertThat(s.contentTypes.get(0)).startsWith("application/json");
        assertThat(s.paths.get(0)).isEqualTo("/hook?x=1");
        JsonNode j = mapper.readTree(s.bodies.get(0));
        assertThat(j.get("event").asText()).isEqualTo("HOST_DOWN");
        assertThat(j.get("hostName").asText()).isEqualTo("prod");
        assertThat(j.get("hostId").asText()).isNotBlank();
        assertThat(j.get("previousStatus").asText()).isEqualTo("UP");
        assertThat(j.get("status").asText()).isEqualTo("DOWN");
        assertThat(j.get("errorCode").asText()).isEqualTo("UNREACHABLE");
        assertThat(Instant.parse(j.get("occurredAt").asText())).isEqualTo(Instant.parse("2026-10-23T10:00:00Z"));
        await().atMost(5, SECONDS).until(() -> "SUCCESS".equals(lastStatus(id)));
        assertThat(jdbc.queryForObject("SELECT last_delivery_at FROM notification_webhooks", java.sql.Timestamp.class))
                .isNotNull();
    }

    @Test
    void containerCrashPayloadCarriesTypeAndTarget() throws Exception {
        Stub s = stub(200, 0);
        Stub other = stub(200, 0);
        Login l = login("admin");
        add(l, "C", s.url("/"), "[\"CONTAINER_CRASHED\",\"DEPLOYMENT_FAILED\"]", true);
        add(l, "H", other.url("/"), "[\"HOST_DOWN\"]", true);
        UUID host = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        UUID deployment = insertDeployment(host);
        publisher.publishEvent(new dev.bethge.shipir.host.ContainerProblemEvent(
                dev.bethge.shipir.host.ContainerProblemEvent.Kind.CRASHED, host, "prod", "abc123", "web-1",
                deployment, "web", 137, Instant.parse("2026-10-23T10:00:00Z")));
        await().atMost(5, SECONDS).until(() -> s.bodies.size() == 1);
        JsonNode j = mapper.readTree(s.bodies.get(0));
        assertThat(j.get("type").asText()).isEqualTo("CONTAINER_CRASHED");
        assertThat(j.get("event").asText()).isEqualTo("CONTAINER_CRASHED");
        assertThat(j.get("exitCode").asInt()).isEqualTo(137);
        JsonNode target = j.get("target");
        assertThat(target.get("hostId").asText()).isEqualTo(host.toString());
        assertThat(target.get("hostName").asText()).isEqualTo("prod");
        assertThat(target.get("deploymentId").asText()).isEqualTo(deployment.toString());
        assertThat(target.get("deploymentName").asText()).isEqualTo("web");
        assertThat(target.get("containerId").asText()).isEqualTo("abc123");
        assertThat(target.get("containerName").asText()).isEqualTo("web-1");
        Thread.sleep(200);
        assertThat(other.bodies).isEmpty();
    }

    @Test
    void deploymentFailedAndAutoUpdateFailedAreDeliveredToSubscribers() throws Exception {
        Stub s = stub(200, 0);
        Login l = login("admin");
        add(l, "D", s.url("/"), "[\"DEPLOYMENT_FAILED\",\"GIT_AUTO_UPDATE_FAILED\"]", true);
        UUID host = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        UUID deployment = insertDeployment(host);
        Instant at = Instant.parse("2026-10-23T10:00:00Z");
        publisher.publishEvent(new dev.bethge.shipir.compose.DeploymentRunFailedEvent(host, "prod", deployment, "web",
                "UP", 1, false, null, at));
        publisher.publishEvent(new dev.bethge.shipir.compose.GitAutoUpdateFailedEvent(host, "prod", deployment, "web",
                "GIT_AUTH_FAILED", at));
        await().atMost(5, SECONDS).until(() -> s.bodies.size() == 2);
        assertThat(s.bodies).anyMatch(b -> b.contains("\"DEPLOYMENT_FAILED\"") && b.contains("\"deploymentName\":\"web\""))
                .anyMatch(b -> b.contains("\"GIT_AUTO_UPDATE_FAILED\"") && b.contains("GIT_AUTH_FAILED"));
    }

    @Test
    void disabledAndUnsubscribedWebhooksGetNothing() throws Exception {
        Stub disabled = stub(200, 0);
        Stub unsubscribed = stub(200, 0);
        Stub active = stub(200, 0);
        Login l = login("admin");
        add(l, "D", disabled.url("/"), "[\"HOST_DOWN\"]", false);
        add(l, "U", unsubscribed.url("/"), "[\"HOST_UP\"]", true);
        add(l, "A", active.url("/"), "[\"HOST_DOWN\"]", true);
        down();
        await().atMost(5, SECONDS).until(() -> active.bodies.size() == 1);
        Thread.sleep(300);
        assertThat(disabled.bodies).isEmpty();
        assertThat(unsubscribed.bodies).isEmpty();
    }

    @Test
    void failingReceiverIsRecordedAndDoesNotAffectOthers() throws Exception {
        Stub failing = stub(500, 0);
        Stub ok = stub(200, 0);
        Login l = login("admin");
        String failId = add(l, "F", failing.url("/"), "[\"HOST_DOWN\"]", true);
        add(l, "O", ok.url("/"), "[\"HOST_DOWN\"]", true);
        down();
        await().atMost(5, SECONDS).until(() -> ok.bodies.size() == 1 && "HTTP_500".equals(lastStatus(failId)));
        assertThat(failing.bodies).hasSize(1);
    }

    @Test
    void slowReceiverDoesNotBlockPublisherOrOthersAndTimesOut() throws Exception {
        Stub slow = stub(200, 4000);
        Stub ok = stub(200, 0);
        Login l = login("admin");
        String slowId = add(l, "S", slow.url("/"), "[\"HOST_DOWN\"]", true);
        add(l, "O", ok.url("/"), "[\"HOST_DOWN\"]", true);
        long start = System.nanoTime();
        down();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
        await().atMost(5, SECONDS).until(() -> ok.bodies.size() == 1);
        await().atMost(5, SECONDS).until(() -> "TIMEOUT".equals(lastStatus(slowId)));
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        Stub target = stub(200, 0);
        Stub redirecting = stub(302, 0);
        redirecting.redirectPort = target.server.getAddress().getPort();
        Login l = login("admin");
        String id = add(l, "R", redirecting.url("/"), "[\"HOST_DOWN\"]", true);
        down();
        await().atMost(5, SECONDS).until(() -> "HTTP_302".equals(lastStatus(id)));
        assertThat(target.bodies).isEmpty();
    }

    @Test
    void testEndpointSendsTestPayloadAndReportsResult() throws Exception {
        Stub ok = stub(200, 0);
        Stub bad = stub(503, 0);
        Login l = login("admin");
        String okId = add(l, "O", ok.url("/"), "[\"HOST_UP\"]", false);
        String badId = add(l, "B", bad.url("/"), "[\"HOST_UP\"]", true);
        mvc.perform(l.write(post("/api/notifications/webhooks/" + okId + "/test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(ok.bodies).hasSize(1);
        assertThat(mapper.readTree(ok.bodies.get(0)).get("event").asText()).isEqualTo("TEST");
        mvc.perform(l.write(post("/api/notifications/webhooks/" + badId + "/test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.status").value("FAILED"));
        // a test delivery records no receiver detail either: the list must not become the oracle
        assertThat(lastStatus(badId)).isEqualTo("FAILED");
        mvc.perform(get("/api/notifications/webhooks").session(l.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("HTTP_"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("TIMEOUT"))));
        mvc.perform(l.write(post("/api/notifications/webhooks/" + UUID.randomUUID() + "/test")))
                .andExpect(status().isNotFound());
    }

    @Test
    void testEndpointReportsTimeoutAsPlainFailureToo() throws Exception {
        Stub slow = stub(200, 4000);
        Login l = login("admin");
        String id = add(l, "S", slow.url("/"), "[\"HOST_UP\"]", true);
        mvc.perform(l.write(post("/api/notifications/webhooks/" + id + "/test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(lastStatus(id)).isEqualTo("FAILED");
    }
}
