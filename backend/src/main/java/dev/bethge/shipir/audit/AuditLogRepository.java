package dev.bethge.shipir.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID>, JpaSpecificationExecutor<AuditLogEntry> {
}
