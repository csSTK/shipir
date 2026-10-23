package dev.bethge.shipir.retention;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.audit.AuditLogRepository;
import dev.bethge.shipir.audit.AuditRecord;
import dev.bethge.shipir.audit.AuditService;
import dev.bethge.shipir.notification.NotificationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

/**
 * Deletes rows strictly older than {@code now - days} (a row exactly at the cutoff is kept; a day is 24 hours).
 * Chat sessions are deleted with native SQL (there is no entity for them here); their messages cascade in the database.
 * Only counts are logged and audited.
 */
@Service
class RetentionPurgeService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeService.class);

    private final RetentionService retention;
    private final AuditLogRepository auditLog;
    private final NotificationRepository notifications;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    RetentionPurgeService(RetentionService retention, AuditLogRepository auditLog,
                          NotificationRepository notifications, AuditService auditService,
                          JdbcTemplate jdbc, Clock clock) {
        this.retention = retention;
        this.auditLog = auditLog;
        this.notifications = notifications;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    PurgeResult purge() {
        RetentionSettings s = retention.load();
        Instant now = clock.instant();
        int audit = auditLog.deleteByOccurredAtBefore(now.minus(Duration.ofDays(s.getAuditLogDays())));
        int notified = notifications.deleteByCreatedAtBefore(now.minus(Duration.ofDays(s.getNotificationDays())));
        int chats = jdbc.update("DELETE FROM chat_sessions WHERE updated_at < ?",
                Timestamp.from(now.minus(Duration.ofDays(s.getChatSessionDays()))));
        log.info("Retention purge deleted {} audit log entries, {} notifications and {} chat sessions",
                audit, notified, chats);
        auditService.record(new AuditRecord(null, "system", "RETENTION_PURGE", AuditLogEntry.Outcome.SUCCESS,
                "RETENTION_SETTINGS", null, Map.of("auditLogDeleted", audit, "notificationsDeleted", notified,
                        "chatSessionsDeleted", chats)));
        return new PurgeResult(audit, notified, chats);
    }

    record PurgeResult(int auditLogDeleted, int notificationsDeleted, int chatSessionsDeleted) {
    }
}
