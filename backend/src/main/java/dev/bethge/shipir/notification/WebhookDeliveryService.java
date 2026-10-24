package dev.bethge.shipir.notification;

import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.compose.DeploymentRunFailedEvent;
import dev.bethge.shipir.compose.GitAutoUpdateFailedEvent;
import dev.bethge.shipir.host.ContainerProblemEvent;
import dev.bethge.shipir.host.HostStatusChangedEvent;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posts host status and deployment/container events as generic JSON to the subscribed, enabled webhooks. Each delivery runs on its own
 * virtual thread, so a slow receiver blocks neither the publisher nor other webhooks. No redirects, no retries.
 * The URL and the response are never logged.
 */
@Service
@EnableConfigurationProperties(WebhookProperties.class)
class WebhookDeliveryService {

    static final String SUCCESS = "SUCCESS";
    static final String TIMEOUT = "TIMEOUT";
    static final String FAILED = "FAILED";

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

    private final NotificationWebhookRepository webhooks;
    private final SecretCipher cipher;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient client;
    private final WebhookProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    WebhookDeliveryService(NotificationWebhookRepository webhooks, SecretCipher cipher, ObjectMapper mapper,
                           Clock clock, WebhookProperties properties) {
        this.webhooks = webhooks;
        this.cipher = cipher;
        this.mapper = mapper;
        this.clock = clock;
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.webhookTimeout())
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @EventListener
    void onHostStatusChanged(HostStatusChangedEvent event) {
        NotificationType type = event.current() == Status.DOWN ? NotificationType.HOST_DOWN : NotificationType.HOST_UP;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", type.name());
        payload.put("type", type.name());
        payload.put("target", target(event.hostId(), event.hostName(), null, null, null, null));
        payload.put("hostId", event.hostId());
        payload.put("hostName", event.hostName());
        payload.put("previousStatus", event.previous());
        payload.put("status", event.current());
        payload.put("errorCode", event.errorCode());
        payload.put("occurredAt", event.occurredAt() != null ? event.occurredAt() : clock.instant());
        dispatch(type, payload);
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    void onDeploymentRunFailed(DeploymentRunFailedEvent event) {
        dispatch(NotificationDraft.of(event));
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    void onGitAutoUpdateFailed(GitAutoUpdateFailedEvent event) {
        dispatch(NotificationDraft.of(event));
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    void onContainerProblem(ContainerProblemEvent event) {
        dispatch(NotificationDraft.of(event));
    }

    private void dispatch(NotificationDraft d) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", d.type().name());
        payload.put("type", d.type().name());
        payload.put("target", target(d.hostId(), d.hostName(), d.deploymentId(), d.deploymentName(), d.containerId(),
                d.containerName()));
        payload.put("hostId", d.hostId());
        payload.put("hostName", d.hostName());
        payload.put("message", d.message());
        payload.put("exitCode", d.exitCode());
        payload.put("errorCode", d.errorCode());
        payload.put("occurredAt", d.occurredAt() != null ? d.occurredAt() : clock.instant());
        dispatch(d.type(), payload);
    }

    private static Map<String, Object> target(Object hostId, String hostName, Object deploymentId,
                                              String deploymentName, String containerId, String containerName) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("hostId", hostId);
        target.put("hostName", hostName);
        target.put("deploymentId", deploymentId);
        target.put("deploymentName", deploymentName);
        target.put("containerId", containerId);
        target.put("containerName", containerName);
        return target;
    }

    private void dispatch(NotificationType type, Map<String, Object> payload) {
        for (NotificationWebhook w : webhooks.findByEnabledTrue()) {
            if (w.subscribedTo(type)) {
                executor.submit(() -> deliverAndRecord(w, payload));
            }
        }
    }

    /** Sends a synthetic event to one webhook, whatever its enabled flag or subscriptions; blocks until done. */
    String sendTest(NotificationWebhook webhook, String hostName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "TEST");
        payload.put("type", "TEST");
        payload.put("target", target(null, hostName, null, null, null, null));
        payload.put("hostId", null);
        payload.put("hostName", hostName);
        payload.put("previousStatus", null);
        payload.put("status", null);
        payload.put("errorCode", null);
        payload.put("occurredAt", clock.instant());
        return deliverAndRecord(webhook, payload);
    }

    private String deliverAndRecord(NotificationWebhook webhook, Map<String, Object> payload) {
        String status = deliver(webhook, payload);
        try {
            webhooks.recordDelivery(webhook.getId(), clock.instant(), status);
        } catch (RuntimeException e) {
            // e.g. webhook deleted meanwhile; the delivery result is not worth failing for
            log.warn("Could not record webhook delivery status ({})", e.getClass().getSimpleName());
        }
        return status;
    }

    private String deliver(NotificationWebhook webhook, Map<String, Object> payload) {
        try {
            URI uri = URI.create(cipher.decrypt(webhook.getUrlEncrypted()));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(properties.webhookTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            int code = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return code >= 200 && code < 300 ? SUCCESS : "HTTP_" + code;
        } catch (HttpTimeoutException e) {
            return TIMEOUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FAILED;
        } catch (IOException | RuntimeException e) {
            // exception messages can contain the URL, so only the type is logged
            log.warn("Webhook delivery failed ({})", e.getClass().getSimpleName());
            return FAILED;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
