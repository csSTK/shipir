package dev.bethge.shipir.notification;

import com.sun.net.httpserver.HttpServer;
import dev.bethge.shipir.common.net.EgressHttpClient;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import dev.bethge.shipir.common.security.SecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Webhook delivery is an SSRF sink: internal targets get no request and fail like any unreachable receiver. */
class WebhookDeliveryEgressTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final SecretCipher cipher = mock(SecretCipher.class);
    private final NotificationWebhookRepository repo = mock(NotificationWebhookRepository.class);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes()));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private WebhookDeliveryService service(boolean allowPrivate) {
        return new WebhookDeliveryService(repo, cipher, JsonMapper.builder().build(), Clock.systemUTC(),
                new WebhookProperties(Duration.ofSeconds(2)),
                new EgressHttpClient(new EgressPolicy(new EgressProperties(allowPrivate)), Duration.ofSeconds(2)));
    }

    private NotificationWebhook webhookTo(String url) {
        NotificationWebhook w = mock(NotificationWebhook.class);
        when(w.getId()).thenReturn(UUID.randomUUID());
        when(w.getUrlEncrypted()).thenReturn(new byte[]{1});
        when(cipher.decrypt(any())).thenReturn(url);
        return w;
    }

    private String loopback() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @Test
    void loopbackReceiverIsRefusedByDefault() {
        assertThat(service(false).sendTest(webhookTo(loopback()), "h")).isEqualTo(WebhookDeliveryService.FAILED);
        assertThat(bodies).isEmpty();
    }

    @Test
    void loopbackReceiverIsDeliveredToWhenPrivateRangesAreAllowed() {
        assertThat(service(true).sendTest(webhookTo(loopback()), "h")).isEqualTo(WebhookDeliveryService.SUCCESS);
        assertThat(bodies).hasSize(1);
    }

    @Test
    void metadataAndLinkLocalAreRefusedEvenWhenPrivateRangesAreAllowed() {
        for (String url : List.of("http://169.254.169.254/latest/meta-data", "http://[fe80::1]/x",
                "http://metadata.google.internal/")) {
            assertThat(service(true).sendTest(webhookTo(url), "h")).as(url).isEqualTo(WebhookDeliveryService.FAILED);
        }
    }
}
