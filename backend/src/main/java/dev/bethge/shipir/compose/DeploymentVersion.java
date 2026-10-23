package dev.bethge.shipir.compose;

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

/** Compose YAML plus the encrypted .env content of a deployment at one point in time. {@code toString} omits both. */
@Entity
@Table(name = "deployment_versions")
public class DeploymentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "deployment_id", nullable = false, updatable = false)
    private UUID deploymentId;

    @Column(name = "yaml_content", nullable = false)
    private String yamlContent;

    @Column(name = "env_ciphertext")
    private byte[] envCiphertext;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private VersionSource source = VersionSource.MANUAL;

    @Column(name = "deployed_at")
    private Instant deployedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DeploymentVersion() {
    }

    public DeploymentVersion(UUID deploymentId, String yamlContent, byte[] envCiphertext, UUID createdBy, Instant now) {
        this.deploymentId = deploymentId;
        this.yamlContent = yamlContent;
        this.envCiphertext = envCiphertext;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public DeploymentVersion(UUID deploymentId, String yamlContent, byte[] envCiphertext, VersionSource source, UUID createdBy,
                        Instant now) {
        this(deploymentId, yamlContent, envCiphertext, createdBy, now);
        this.source = source;
    }

    public UUID getId() { return id; }
    public UUID getDeploymentId() { return deploymentId; }
    public String getYamlContent() { return yamlContent; }
    public byte[] getEnvCiphertext() { return envCiphertext; }
    public String getCommitSha() { return commitSha; }
    public VersionSource getSource() { return source; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeployedAt() { return deployedAt; }

    /** True once a deploy or rollback used this version; its content must not change any more. */
    public boolean isDeployed() { return deployedAt != null; }

    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }

    public void markDeployed(Instant now) { this.deployedAt = now; }

    public void replaceContent(String yamlContent, byte[] envCiphertext) {
        this.yamlContent = yamlContent;
        this.envCiphertext = envCiphertext;
    }

    @Override
    public String toString() {
        return "DeploymentVersion[id=" + id + ", deploymentId=" + deploymentId + "]";
    }
}
