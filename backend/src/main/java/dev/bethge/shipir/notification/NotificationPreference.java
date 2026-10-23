package dev.bethge.shipir.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.UUID;

/** Explicit per-user opt-in/out; a missing row means enabled. */
@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {

    @Embeddable
    public record Key(@Column(name = "user_id") UUID userId,
                      @Enumerated(EnumType.STRING) @Column(length = 32) NotificationType type)
            implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Column(name = "in_app_enabled", nullable = false)
    private boolean inAppEnabled;

    protected NotificationPreference() {
    }

    NotificationPreference(UUID userId, NotificationType type, boolean inAppEnabled) {
        this.id = new Key(userId, type);
        this.inAppEnabled = inAppEnabled;
    }

    NotificationType getType() { return id.type(); }
    UUID getUserId() { return id.userId(); }
    boolean isInAppEnabled() { return inAppEnabled; }
    void setInAppEnabled(boolean v) { this.inAppEnabled = v; }
}
