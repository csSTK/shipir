package dev.bethge.shipir.notification;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.notification.dto.WebhookRequest;
import dev.bethge.shipir.notification.dto.WebhookResponse;
import dev.bethge.shipir.notification.dto.WebhookTestResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class WebhookService {

    private final NotificationWebhookRepository webhooks;
    private final UserRepository users;
    private final SecretCipher cipher;
    private final WebhookDeliveryService delivery;
    private final Clock clock;

    WebhookService(NotificationWebhookRepository webhooks, UserRepository users, SecretCipher cipher,
                   WebhookDeliveryService delivery, Clock clock) {
        this.webhooks = webhooks;
        this.users = users;
        this.cipher = cipher;
        this.delivery = delivery;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WebhookResponse> list() {
        return webhooks.findAll().stream()
                .sorted(Comparator.comparing(NotificationWebhook::getName, String.CASE_INSENSITIVE_ORDER))
                .map(WebhookService::toResponse)
                .toList();
    }

    @Auditable(action = "WEBHOOK_CREATE", targetType = "WEBHOOK", targetId = "#result?.id")
    WebhookResponse create(WebhookRequest request, String username) {
        if (request.url() == null || request.url().isBlank()) {
            throw new WebhookException(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK_URL", "A URL is required");
        }
        String hint = hint(request.url());
        requireKey();
        if (webhooks.existsByName(request.name())) {
            throw nameTaken();
        }
        UUID ownerId = users.findByUsername(username).orElseThrow().getId();
        NotificationWebhook w = new NotificationWebhook(request.name(), cipher.encrypt(request.url()), hint,
                request.eventTypes(), request.enabledOrDefault(), ownerId, clock.instant());
        try {
            return toResponse(webhooks.saveAndFlush(w));
        } catch (DataIntegrityViolationException e) {
            throw nameTaken();
        }
    }

    @Auditable(action = "WEBHOOK_UPDATE", targetType = "WEBHOOK", targetId = "#id")
    @Transactional
    public WebhookResponse update(UUID id, WebhookRequest request) {
        NotificationWebhook w = find(id);
        if (!w.getName().equals(request.name()) && webhooks.existsByName(request.name())) {
            throw nameTaken();
        }
        if (request.url() != null && !request.url().isBlank()) {
            String hint = hint(request.url());
            requireKey();
            w.replaceUrl(cipher.encrypt(request.url()), hint);
        }
        w.update(request.name(), request.eventTypes(), request.enabledOrDefault(), clock.instant());
        try {
            webhooks.flush();
        } catch (DataIntegrityViolationException e) {
            throw nameTaken();
        }
        return toResponse(w);
    }

    @Auditable(action = "WEBHOOK_DELETE", targetType = "WEBHOOK", targetId = "#id")
    @Transactional
    public void delete(UUID id) {
        webhooks.delete(find(id));
        webhooks.flush();
    }

    /** Not transactional: the HTTP call must not hold a database connection. */
    WebhookTestResponse test(UUID id) {
        NotificationWebhook w = webhooks.findById(id).orElseThrow(WebhookService::notFound);
        String status = delivery.sendTest(w, "Shipir test");
        // only success or failure: the HTTP status/timeout of an arbitrary target would make this an internal port scanner
        boolean ok = WebhookDeliveryService.SUCCESS.equals(status);
        return new WebhookTestResponse(ok, ok ? WebhookDeliveryService.SUCCESS : WebhookDeliveryService.FAILED);
    }

    private NotificationWebhook find(UUID id) {
        return webhooks.findById(id).orElseThrow(WebhookService::notFound);
    }

    private void requireKey() {
        if (!cipher.isConfigured()) {
            throw new WebhookException(HttpStatus.CONFLICT, "ENCRYPTION_KEY_MISSING",
                    "Webhooks need shipir.encryption-key (SHIPIR_ENCRYPTION_KEY) to store their URL");
        }
    }

    /** Absolute http(s) URL with a host; returns scheme://host[:port] for display. */
    static String hint(String url) {
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw invalidUrl();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalidUrl();
        }
        return scheme.toLowerCase() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
    }

    private static WebhookException invalidUrl() {
        return new WebhookException(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK_URL",
                "The URL must be an absolute http or https URL");
    }

    private static WebhookException notFound() {
        return new WebhookException(HttpStatus.NOT_FOUND, "WEBHOOK_NOT_FOUND", "Webhook not found");
    }

    private static WebhookException nameTaken() {
        return new WebhookException(HttpStatus.CONFLICT, "WEBHOOK_NAME_TAKEN",
                "A webhook with this name already exists");
    }

    private static WebhookResponse toResponse(NotificationWebhook w) {
        return new WebhookResponse(w.getId(), w.getName(), w.getUrlHint(), w.getEventTypes(), w.isEnabled(),
                w.getCreatedAt(), w.getLastDeliveryAt(), w.getLastDeliveryStatus());
    }
}
