package dev.bethge.shipir.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One in-app notification for one user; {@code hostName} is a snapshot, {@code hostId} is null once the host is deleted. */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private NotificationType type;

    @Column(name = "host_id", updatable = false)
    private UUID hostId;

    @Column(name = "host_name", nullable = false, updatable = false)
    private String hostName;

    @Column(nullable = false, updatable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    Notification(UUID userId, NotificationType type, UUID hostId, String hostName, String message, Instant createdAt) {
        this.userId = userId;
        this.type = type;
        this.hostId = hostId;
        this.hostName = hostName;
        this.message = message;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public NotificationType getType() { return type; }
    public UUID getHostId() { return hostId; }
    public String getHostName() { return hostName; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isRead() { return readAt != null; }
}
