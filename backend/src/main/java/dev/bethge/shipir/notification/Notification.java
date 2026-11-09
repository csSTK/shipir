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

/**
 * One in-app notification for one user; {@code hostName}, {@code deploymentName} and {@code containerName} are snapshots,
 * {@code hostId}/{@code deploymentId} are null once the host/deployment is deleted (or never set: host events have no deployment).
 */
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

    @Column(name = "deployment_id", updatable = false)
    private UUID deploymentId;

    @Column(name = "deployment_name", updatable = false)
    private String deploymentName;

    @Column(name = "container_id", updatable = false)
    private String containerId;

    @Column(name = "container_name", updatable = false)
    private String containerName;

    @Column(nullable = false, updatable = false, length = 500)
    private String message;

    @Column(name = "error_code", updatable = false, length = 64)
    private String errorCode;

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

    Notification(UUID userId, NotificationDraft draft, Instant createdAt) {
        this(userId, draft.type(), draft.hostId(), draft.hostName(), draft.message(), createdAt);
        this.deploymentId = draft.deploymentId();
        this.deploymentName = draft.deploymentName();
        this.containerId = draft.containerId();
        this.containerName = draft.containerName();
        // only IMAGE_UPDATE_FAILED reasons are a fixed code set; other drafts carry free text or are not shown by code
        if (draft.type() == NotificationType.IMAGE_UPDATE_FAILED && draft.errorCode() != null) {
            this.errorCode = draft.errorCode().length() <= 64 ? draft.errorCode() : draft.errorCode().substring(0, 64);
        }
    }

    public UUID getId() { return id; }
    public NotificationType getType() { return type; }
    public UUID getHostId() { return hostId; }
    public String getHostName() { return hostName; }
    public UUID getDeploymentId() { return deploymentId; }
    public String getDeploymentName() { return deploymentName; }
    public String getContainerId() { return containerId; }
    public String getContainerName() { return containerName; }
    public String getMessage() { return message; }
    public String getErrorCode() { return errorCode; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isRead() { return readAt != null; }
}
