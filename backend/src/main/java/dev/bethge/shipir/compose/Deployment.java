package dev.bethge.shipir.compose;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** A Compose deployment managed by Shipir on one environment. The compose file and .env live on disk, see {@code ComposeFileRepository}. */
@Entity
@Table(name = "deployments")
public class Deployment {

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
    private DeploymentStatus status = DeploymentStatus.STOPPED;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 10)
    private DeploymentSource sourceType = DeploymentSource.FILE;

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

    @Column(name = "auto_update_enabled", nullable = false)
    private boolean autoUpdateEnabled;

    @Column(name = "auto_update_interval_seconds")
    private Integer autoUpdateIntervalSeconds;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "last_poll_error", length = 50)
    private String lastPollError;

    @Column(name = "image_auto_update", nullable = false)
    private boolean imageAutoUpdate;

    @Column(name = "image_update_window_start")
    private LocalTime imageUpdateWindowStart;

    @Column(name = "image_update_window_end")
    private LocalTime imageUpdateWindowEnd;

    @Column(name = "image_update_last_attempt_at")
    private Instant imageUpdateLastAttemptAt;

    @Column(name = "image_update_last_result", length = 30)
    private String imageUpdateLastResult;

    /** Compose profiles activated for every run, in the order given; empty = none. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> profiles = List.of();

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "last_deployed_at")
    private Instant lastDeployedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    protected Deployment() {
    }

    public Deployment(String name, UUID hostId, String composeFilePath, UUID createdBy, Instant now) {
        this.name = name;
        this.hostId = hostId;
        this.composeFilePath = composeFilePath;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    /** Makes this a Git-sourced deployment; the credential is optional (public repositories). */
    public void useGit(String repoUrl, String ref, String composePath, UUID credentialId) {
        this.sourceType = DeploymentSource.GIT;
        this.gitRepoUrl = repoUrl;
        this.gitRef = ref;
        this.gitComposePath = composePath;
        this.gitCredentialId = credentialId;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getHostId() { return hostId; }
    public String getComposeFilePath() { return composeFilePath; }
    public DeploymentStatus getStatus() { return status; }
    public DeploymentSource getSourceType() { return sourceType; }
    public String getGitRepoUrl() { return gitRepoUrl; }
    public String getGitRef() { return gitRef; }
    public String getGitComposePath() { return gitComposePath; }
    public UUID getGitCredentialId() { return gitCredentialId; }
    public String getLastCommitSha() { return lastCommitSha; }
    public boolean isAutoUpdateEnabled() { return autoUpdateEnabled; }
    public Integer getAutoUpdateIntervalSeconds() { return autoUpdateIntervalSeconds; }
    public Instant getLastPolledAt() { return lastPolledAt; }
    public String getLastPollError() { return lastPollError; }
    public boolean isImageAutoUpdate() { return imageAutoUpdate; }
    public LocalTime getImageUpdateWindowStart() { return imageUpdateWindowStart; }
    public LocalTime getImageUpdateWindowEnd() { return imageUpdateWindowEnd; }
    public Instant getImageUpdateLastAttemptAt() { return imageUpdateLastAttemptAt; }
    public String getImageUpdateLastResult() { return imageUpdateLastResult; }
    public List<String> getProfiles() { return profiles; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getLastDeployedAt() { return lastDeployedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getCurrentVersionId() { return currentVersionId; }

    public void configureAutoUpdate(boolean enabled, Integer intervalSeconds) {
        this.autoUpdateEnabled = enabled;
        if (intervalSeconds != null) {
            this.autoUpdateIntervalSeconds = intervalSeconds;
        }
        if (!enabled) {
            this.lastPollError = null;
        }
    }

    /** Image auto-update on or off with its daily UTC window ({@code null} window = any time). */
    public void configureImageAutoUpdate(boolean enabled, ImageUpdateWindow window) {
        this.imageAutoUpdate = enabled;
        this.imageUpdateWindowStart = window == null ? null : window.start();
        this.imageUpdateWindowEnd = window == null ? null : window.end();
    }

    /** The window, or null when the deployment may update at any time. */
    ImageUpdateWindow imageUpdateWindow() {
        return imageUpdateWindowStart == null ? null : new ImageUpdateWindow(imageUpdateWindowStart, imageUpdateWindowEnd);
    }

    public void recordPoll(Instant at, String error) {
        this.lastPolledAt = at;
        this.lastPollError = error;
    }

    public void setProfiles(List<String> profiles) { this.profiles = List.copyOf(profiles); }
    public void setLastCommitSha(String lastCommitSha) { this.lastCommitSha = lastCommitSha; }
    public void setStatus(DeploymentStatus status) { this.status = status; }
    public void setLastDeployedAt(Instant lastDeployedAt) { this.lastDeployedAt = lastDeployedAt; }
    public void setCurrentVersionId(UUID currentVersionId) { this.currentVersionId = currentVersionId; }
}
