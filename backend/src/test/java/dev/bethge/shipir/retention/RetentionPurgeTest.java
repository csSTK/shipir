package dev.bethge.shipir.retention;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionPurgeTest extends RetentionTestBase {

    @Autowired
    RetentionPurgeService purge;
    @Autowired
    ApplicationContext context;

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void deletesOldAuditRowsAndKeepsNewOnes() {
        insertAudit(NOW.minus(Duration.ofDays(366)), "OLD");
        insertAudit(NOW.minus(Duration.ofDays(364)), "NEW");
        purge.purge();
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'OLD'")).isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'NEW'")).isEqualTo(1);
    }

    @Test
    void deletesOldNotificationsAndKeepsNewOnes() {
        insertNotification(NOW.minus(Duration.ofDays(31)));
        insertNotification(NOW.minus(Duration.ofDays(29)));
        PurgeCounts c = counts();
        assertThat(c.notifications).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications")).isEqualTo(1);
    }

    /** Boundary: a row exactly {@code days} old is kept, one second older is deleted. */
    @Test
    void rowExactlyAtCutoffIsKeptOneSecondOlderIsDeleted() {
        insertAudit(NOW.minus(Duration.ofDays(365)), "AT_CUTOFF");
        insertAudit(NOW.minus(Duration.ofDays(365)).minusSeconds(1), "BEFORE_CUTOFF");
        insertNotification(NOW.minus(Duration.ofDays(30)));
        insertNotification(NOW.minus(Duration.ofDays(30)).minusSeconds(1));
        purge.purge();
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'AT_CUTOFF'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'BEFORE_CUTOFF'")).isZero();
        assertThat(count("SELECT count(*) FROM notifications")).isEqualTo(1);
    }

    @Test
    void usesConfiguredDays() {
        jdbc.update("UPDATE retention_settings SET audit_log_days = 10, notification_days = 5");
        insertAudit(NOW.minus(Duration.ofDays(11)), "OLD");
        insertAudit(NOW.minus(Duration.ofDays(9)), "NEW");
        insertNotification(NOW.minus(Duration.ofDays(6)));
        insertNotification(NOW.minus(Duration.ofDays(4)));
        purge.purge();
        assertThat(count("SELECT count(*) FROM audit_log WHERE action IN ('OLD','NEW')")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications")).isEqualTo(1);
    }

    @Test
    void writesOneSystemAuditEntryWithCounts() {
        insertAudit(NOW.minus(Duration.ofDays(400)), "OLD");
        insertAudit(NOW.minus(Duration.ofDays(401)), "OLD");
        insertNotification(NOW.minus(Duration.ofDays(40)));
        purge.purge();
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'RETENTION_PURGE' "
                + "AND username = 'system' AND user_id IS NULL")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT details->>'auditLogDeleted' FROM audit_log "
                + "WHERE action = 'RETENTION_PURGE'", String.class)).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT details->>'notificationsDeleted' FROM audit_log "
                + "WHERE action = 'RETENTION_PURGE'", String.class)).isEqualTo("1");
    }

    @Test
    void purgeJobIsDisabledByPropertyInTests() {
        assertThat(context.getBeanNamesForType(RetentionPurgeJob.class)).isEmpty();
    }

    private record PurgeCounts(int audit, int notifications) {
    }

    private PurgeCounts counts() {
        RetentionPurgeService.PurgeResult r = purge.purge();
        return new PurgeCounts(r.auditLogDeleted(), r.notificationsDeleted());
    }
}
