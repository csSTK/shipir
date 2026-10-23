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

/** A Compose stack managed by Shipir on one environment. The compose file and .env live on disk, see {@code ComposeFileRepository}. */
@Entity
@Table(name = "stacks")
public class Stack {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "compose_file_path", nullable = false, length = 500)
    private String composeFilePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StackStatus status = StackStatus.STOPPED;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 10)
    private StackSource sourceType = StackSource.FILE;

    @Column(name = "git_repo_url", length = 500)
    private String gitRepoUrl;

    @Column(name = "git_ref")
    private String gitRef;

    @Column(name = "git_compose_path", length = 500)
    private String gitComposePath;

    @Column(name = "git_credential_id")
    private UUID gitCredentialId;

    @Column(name = "last_commit_sha", length = 64)
    private String lastCommitSha;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "last_deployed_at")
    private Instant lastDeployedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    protected Stack() {
    }

    public Stack(String name, UUID hostId, String composeFilePath, UUID createdBy, Instant now) {
        this.name = name;
        this.hostId = hostId;
        this.composeFilePath = composeFilePath;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    /** Makes this a Git-sourced stack; the credential is optional (public repositories). */
    public void useGit(String repoUrl, String ref, String composePath, UUID credentialId) {
        this.sourceType = StackSource.GIT;
        this.gitRepoUrl = repoUrl;
        this.gitRef = ref;
        this.gitComposePath = composePath;
        this.gitCredentialId = credentialId;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getHostId() { return hostId; }
    public String getComposeFilePath() { return composeFilePath; }
    public StackStatus getStatus() { return status; }
    public StackSource getSourceType() { return sourceType; }
    public String getGitRepoUrl() { return gitRepoUrl; }
    public String getGitRef() { return gitRef; }
    public String getGitComposePath() { return gitComposePath; }
    public UUID getGitCredentialId() { return gitCredentialId; }
    public String getLastCommitSha() { return lastCommitSha; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getLastDeployedAt() { return lastDeployedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getCurrentVersionId() { return currentVersionId; }

    public void setLastCommitSha(String lastCommitSha) { this.lastCommitSha = lastCommitSha; }
    public void setStatus(StackStatus status) { this.status = status; }
    public void setLastDeployedAt(Instant lastDeployedAt) { this.lastDeployedAt = lastDeployedAt; }
    public void setCurrentVersionId(UUID currentVersionId) { this.currentVersionId = currentVersionId; }
}
