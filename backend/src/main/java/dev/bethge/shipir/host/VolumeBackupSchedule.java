package dev.bethge.shipir.host;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** A recurring backup of one volume on one host; the archives live on disk, not in the database. */
@Entity
@Table(name = "volume_backup_schedules")
public class VolumeBackupSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "volume_name", nullable = false, updatable = false, length = 128)
    private String volumeName;

    @Column(name = "interval_hours", nullable = false)
    private int intervalHours;

    @Column(name = "keep_count", nullable = false)
    private int keepCount;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_status", length = 16)
    private String lastStatus;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VolumeBackupSchedule() {
    }

    VolumeBackupSchedule(UUID hostId, String volumeName, int intervalHours, int keepCount, UUID createdBy, Instant now) {
        this.hostId = hostId;
        this.volumeName = volumeName;
        this.intervalHours = intervalHours;
        this.keepCount = keepCount;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.nextRunAt = now;
    }

    /** Records a finished run and plans the next one one interval from {@code now}, also after a failure. */
    void recordRun(boolean success, Instant now) {
        this.lastRunAt = now;
        this.lastStatus = success ? "SUCCESS" : "FAILED";
        this.nextRunAt = now.plus(Duration.ofHours(intervalHours));
    }

    public UUID getId() {
        return id;
    }

    public UUID getHostId() {
        return hostId;
    }

    public String getVolumeName() {
        return volumeName;
    }

    public int getIntervalHours() {
        return intervalHours;
    }

    public int getKeepCount() {
        return keepCount;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public String getLastStatus() {
        return lastStatus;
    }
}
