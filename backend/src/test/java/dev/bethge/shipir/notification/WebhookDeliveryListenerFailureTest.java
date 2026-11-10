package dev.bethge.shipir.notification;

import dev.bethge.shipir.host.HostStatusChangedEvent;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.time.Instant;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** A failing in-app notification listener must not keep the HOST_DOWN webhook from being delivered. */
class WebhookDeliveryListenerFailureTest extends WebhookTestBase {

    @TestConfiguration
    static class Failing {
        @Bean
        Object failingHostListener() {
            return new Object() {
                @EventListener
                @Order(0)
                void on(HostStatusChangedEvent e) {
                    throw new IllegalStateException("notification listener down");
                }
            };
        }
    }

    @Autowired ApplicationEventPublisher publisher;

    @Test
    void webhookIsDeliveredEvenIfAnotherListenerThrows() throws Exception {
        Stub s = stub(200, 0);
        Login l = login("admin");
        mvc.perform(l.write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/notifications/webhooks"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(createJson("A", s.url("/hook"), "[\"HOST_DOWN\"]", true)));
        UUID host = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);

        assertThatThrownBy(() -> publisher.publishEvent(new HostStatusChangedEvent(host, "prod", Status.UP,
                Status.DOWN, "UNREACHABLE", Instant.parse("2026-10-23T10:00:00Z"))))
                .hasMessageContaining("notification listener down");

        await().atMost(5, SECONDS).until(() -> s.bodies.size() == 1);
        assertThat(s.bodies.get(0)).contains("HOST_DOWN");
    }
}
