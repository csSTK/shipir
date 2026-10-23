package dev.bethge.shipir.retention;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Singleton row (id = 1, enforced by a CHECK constraint). */
@Entity
@Table(name = "retention_settings")
class RetentionSettings {

    static final short SINGLETON_ID = 1;

    @Id
    private short id = SINGLETON_ID;

    @Column(name = "audit_log_days", nullable = false)
    private int auditLogDays;

    @Column(name = "notification_days", nullable = false)
    private int notificationDays;

    @Column(name = "chat_session_days", nullable = false)
    private int chatSessionDays;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected RetentionSettings() {
    }

    int getAuditLogDays() { return auditLogDays; }
    int getNotificationDays() { return notificationDays; }
    int getChatSessionDays() { return chatSessionDays; }
    Instant getUpdatedAt() { return updatedAt; }

    /** A null {@code chatSessionDays} keeps the current value (requests from clients that predate the field). */
    void update(int auditLogDays, int notificationDays, Integer chatSessionDays, Instant now, UUID by) {
        this.auditLogDays = auditLogDays;
        this.notificationDays = notificationDays;
        if (chatSessionDays != null) {
            this.chatSessionDays = chatSessionDays;
        }
        this.updatedAt = now;
        this.updatedBy = by;
    }
}
