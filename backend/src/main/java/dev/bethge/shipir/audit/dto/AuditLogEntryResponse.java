package dev.bethge.shipir.audit.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogEntryResponse(UUID id, Instant occurredAt, String username, String action, String outcome,
                                    String targetType, String targetId, Map<String, Object> details) {
}
