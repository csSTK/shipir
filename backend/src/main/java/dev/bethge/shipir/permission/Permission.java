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

/**
 * A grant to exactly one subject (a user or a team, V26 check) on exactly one target (a host or a deployment,
 * V22 check).
 */
@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "team_id", updatable = false)
    private UUID teamId;

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

    Permission(UUID userId, UUID teamId, UUID hostId, UUID deploymentId, PermissionLevel level, Instant createdAt) {
        this.userId = userId;
        this.teamId = teamId;
        this.hostId = hostId;
        this.deploymentId = deploymentId;
        this.level = level;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getTeamId() { return teamId; }
    public UUID getHostId() { return hostId; }
    public UUID getDeploymentId() { return deploymentId; }
    public PermissionLevel getLevel() { return level; }
    public Instant getCreatedAt() { return createdAt; }
}
