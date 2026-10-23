package dev.bethge.shipir.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily purge (cron {@code shipir.retention.purge-cron}, default 03:00 UTC). Disable with {@code shipir.retention.purge-enabled=false}. */
@Component
@ConditionalOnProperty(name = "shipir.retention.purge-enabled", matchIfMissing = true)
class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);

    private final RetentionPurgeService purgeService;

    RetentionPurgeJob(RetentionPurgeService purgeService) {
        this.purgeService = purgeService;
    }

    @Scheduled(cron = "${shipir.retention.purge-cron:0 0 3 * * *}", zone = "UTC")
    void run() {
        try {
            purgeService.purge();
        } catch (RuntimeException e) {
            log.error("Retention purge failed: {}", e.toString());
        }
    }
}
