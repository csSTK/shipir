package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.SystemScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Every {@code shipir.volume-backup.tick} looks up the schedules that are due and runs their backups one after the
 * other. A failing run is marked FAILED and planned again one interval later; it never stops the others.
 * Disable with {@code shipir.volume-backup.scheduler-enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "shipir.volume-backup.scheduler-enabled", matchIfMissing = true)
class VolumeBackupScheduler {

    private static final Logger log = LoggerFactory.getLogger(VolumeBackupScheduler.class);

    private final VolumeBackupScheduleRepository schedules;
    private final VolumeBackupScheduleService scheduleService;
    private final VolumeBackupService backups;
    private final VolumeBackupStore store;
    private final Clock clock;

    VolumeBackupScheduler(VolumeBackupScheduleRepository schedules, VolumeBackupScheduleService scheduleService,
                          VolumeBackupService backups, VolumeBackupStore store, Clock clock) {
        this.schedules = schedules;
        this.scheduleService = scheduleService;
        this.backups = backups;
        this.store = store;
        this.clock = clock;
    }

    /**
     * Removes {@code .backup-*.part} files a crash left behind. At application start no backup of this single
     * instance is running, so every partial file is stale (minimum age zero).
     */
    @EventListener(ApplicationReadyEvent.class)
    void pruneStalePartialsOnStart() {
        try {
            int removed = store.pruneStalePartials(Duration.ZERO);
            if (removed > 0) {
                log.info("Removed {} stale partial volume backup file(s)", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Could not clean up stale partial volume backup files: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${shipir.volume-backup.tick:1m}", initialDelayString = "${shipir.volume-backup.tick:1m}")
    synchronized void runDue() {
        SystemScope.run(() -> {
            for (VolumeBackupSchedule schedule : schedules.findByNextRunAtLessThanEqualOrderByNextRunAt(clock.instant())) {
                boolean success = true;
                try {
                    backups.storeScheduledBackup(schedule.getHostId(), schedule.getVolumeName(), schedule.getKeepCount());
                } catch (RuntimeException e) {
                    success = false;
                    log.warn("Scheduled backup of volume {} on host {} failed: {}", schedule.getVolumeName(),
                            schedule.getHostId(), e.getClass().getSimpleName());
                }
                try {
                    scheduleService.recordRun(schedule.getId(), success);
                } catch (RuntimeException e) {
                    log.warn("Could not record the run of the backup schedule for volume {} on host {}: {}",
                            schedule.getVolumeName(), schedule.getHostId(), e.getClass().getSimpleName());
                }
            }
        });
    }
}
