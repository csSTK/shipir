package dev.bethge.shipir.audit.dto;

import java.util.List;

public record AuditLogPageResponse(List<AuditLogEntryResponse> items, int page, int size, long totalElements) {
}
