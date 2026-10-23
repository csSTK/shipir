package dev.bethge.shipir.notification.dto;

import dev.bethge.shipir.notification.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Create and update body. {@code url} is required on create; on update null keeps the stored URL.
 * {@code enabled} defaults to true when absent.
 */
public record WebhookRequest(@NotBlank @Size(max = 255) String name,
                             @Size(max = WebhookRequest.MAX_URL_LENGTH) String url,
                             @NotEmpty List<NotificationType> eventTypes,
                             Boolean enabled) {

    public static final int MAX_URL_LENGTH = 2048;

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    /** The URL can carry credentials, so it is left out. */
    @Override
    public String toString() {
        return "WebhookRequest[name=" + name + ", url=<hidden>, eventTypes=" + eventTypes + ", enabled=" + enabled + "]";
    }
}
