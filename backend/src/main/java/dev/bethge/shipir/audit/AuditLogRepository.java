package dev.bethge.shipir.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID>, JpaSpecificationExecutor<AuditLogEntry> {

    /** Bulk delete for the retention purge; returns the number of deleted rows. */
    @Modifying
    @Query("delete from AuditLogEntry e where e.occurredAt < :cutoff")
    int deleteByOccurredAtBefore(Instant cutoff);
}
