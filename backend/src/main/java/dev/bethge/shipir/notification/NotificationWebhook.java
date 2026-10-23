package dev.bethge.shipir.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Receiver of notification events. The URL exists only as ciphertext; {@code urlHint} is scheme + host for display. */
@Entity
@Table(name = "notification_webhooks")
public class NotificationWebhook {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "url_encrypted", nullable = false)
    private byte[] urlEncrypted;

    @Column(name = "url_hint", nullable = false, length = 255)
    private String urlHint;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "event_types", nullable = false, columnDefinition = "varchar(32)[]")
    private String[] eventTypes;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_delivery_at")
    private Instant lastDeliveryAt;

    @Column(name = "last_delivery_status", length = 32)
    private String lastDeliveryStatus;

    protected NotificationWebhook() {
    }

    NotificationWebhook(String name, byte[] urlEncrypted, String urlHint, List<NotificationType> eventTypes,
                        boolean enabled, UUID createdBy, Instant now) {
        this.name = name;
        this.urlEncrypted = urlEncrypted;
        this.urlHint = urlHint;
        this.eventTypes = toArray(eventTypes);
        this.enabled = enabled;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(String name, List<NotificationType> eventTypes, boolean enabled, Instant now) {
        this.name = name;
        this.eventTypes = toArray(eventTypes);
        this.enabled = enabled;
        this.updatedAt = now;
    }

    void replaceUrl(byte[] urlEncrypted, String urlHint) {
        this.urlEncrypted = urlEncrypted;
        this.urlHint = urlHint;
    }

    boolean subscribedTo(NotificationType type) {
        return List.of(eventTypes).contains(type.name());
    }

    private static String[] toArray(List<NotificationType> types) {
        return types.stream().distinct().map(Enum::name).toArray(String[]::new);
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    byte[] getUrlEncrypted() { return urlEncrypted; }
    public String getUrlHint() { return urlHint; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastDeliveryAt() { return lastDeliveryAt; }
    public String getLastDeliveryStatus() { return lastDeliveryStatus; }

    public List<NotificationType> getEventTypes() {
        return java.util.Arrays.stream(eventTypes).map(NotificationType::valueOf).toList();
    }
}
