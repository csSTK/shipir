package dev.bethge.shipir.retention;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.audit.AuditLogRepository;
import dev.bethge.shipir.audit.AuditRecord;
import dev.bethge.shipir.audit.AuditService;
import dev.bethge.shipir.notification.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Deletes rows strictly older than {@code now - days} (a row exactly at the cutoff is kept; a day is 24 hours).
 * Only counts are logged and audited.
 */
@Service
class RetentionPurgeService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeService.class);

    private final RetentionService retention;
    private final AuditLogRepository auditLog;
    private final NotificationRepository notifications;
    private final AuditService auditService;
    private final Clock clock;

    RetentionPurgeService(RetentionService retention, AuditLogRepository auditLog,
                          NotificationRepository notifications, AuditService auditService, Clock clock) {
        this.retention = retention;
        this.auditLog = auditLog;
        this.notifications = notifications;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    PurgeResult purge() {
        RetentionSettings s = retention.load();
        Instant now = clock.instant();
        int audit = auditLog.deleteByOccurredAtBefore(now.minus(Duration.ofDays(s.getAuditLogDays())));
        int notified = notifications.deleteByCreatedAtBefore(now.minus(Duration.ofDays(s.getNotificationDays())));
        log.info("Retention purge deleted {} audit log entries and {} notifications", audit, notified);
        auditService.record(new AuditRecord(null, "system", "RETENTION_PURGE", AuditLogEntry.Outcome.SUCCESS,
                "RETENTION_SETTINGS", null, Map.of("auditLogDeleted", audit, "notificationsDeleted", notified)));
        return new PurgeResult(audit, notified);
    }

    record PurgeResult(int auditLogDeleted, int notificationsDeleted) {
    }
}
