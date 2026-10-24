package dev.bethge.shipir.permission;

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

/** A grant to one user on exactly one target: a host or a deployment (see V21 check constraint). */
@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", updatable = false)
    private UUID hostId;

    @Column(name = "deployment_id", updatable = false)
    private UUID deploymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PermissionLevel level;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Permission() {
    }

    Permission(UUID userId, UUID hostId, UUID deploymentId, PermissionLevel level, Instant createdAt) {
        this.userId = userId;
        this.hostId = hostId;
        this.deploymentId = deploymentId;
        this.level = level;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getHostId() { return hostId; }
    public UUID getDeploymentId() { return deploymentId; }
    public PermissionLevel getLevel() { return level; }
    public Instant getCreatedAt() { return createdAt; }
}
