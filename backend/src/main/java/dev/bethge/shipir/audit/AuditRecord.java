package dev.bethge.shipir.audit;

import java.util.Map;
import java.util.UUID;

/** Input of {@link AuditService#record}. {@code details} must never contain secrets. */
public record AuditRecord(UUID userId, String username, String action, AuditLogEntry.Outcome outcome,
                          String targetType, String targetId, Map<String, Object> details) {
}
