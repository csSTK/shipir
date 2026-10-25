package dev.bethge.shipir.compose;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** The redeploy webhook of one deployment. Only the SHA-256 hex digest of its token is stored. */
@Entity
@Table(name = "deployment_webhooks")
public class DeploymentWebhook {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "deployment_id", nullable = false, updatable = false)
    private UUID deploymentId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    protected DeploymentWebhook() {
    }

    DeploymentWebhook(UUID deploymentId, String tokenHash, Instant createdAt) {
        this.deploymentId = deploymentId;
        this.tokenHash = tokenHash;
        this.enabled = true;
        this.createdAt = createdAt;
    }

    /** Replaces the token (old one stops working) and enables the webhook. */
    void issue(String newTokenHash, Instant at) {
        this.tokenHash = newTokenHash;
        this.enabled = true;
        this.rotatedAt = at;
    }

    void disable() {
        this.enabled = false;
    }

    UUID getDeploymentId() {
        return deploymentId;
    }

    String getTokenHash() {
        return tokenHash;
    }

    boolean isEnabled() {
        return enabled;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getRotatedAt() {
        return rotatedAt;
    }
}
