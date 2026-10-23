package dev.bethge.shipir.audit;

import dev.bethge.shipir.audit.dto.AuditLogEntryResponse;
import dev.bethge.shipir.audit.dto.AuditLogPageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Read side of the audit log: filtered, paged, newest first. Builds DTOs here (no open-session-in-view). */
@Service
public class AuditLogQueryService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    private final AuditLogRepository repository;

    AuditLogQueryService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AuditLogPageResponse find(int page, int size, String action, String username, String from, String to) {
        if (page < 0) {
            throw new InvalidAuditQueryException("page must be >= 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidAuditQueryException("size must be between 1 and " + MAX_SIZE);
        }
        Instant fromInstant = parse("from", from);
        Instant toInstant = parse("to", to);

        Specification<AuditLogEntry> spec = Specification.unrestricted();
        if (hasText(action)) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("action"), action));
        }
        if (hasText(username)) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("username"), username));
        }
        if (fromInstant != null) {
            spec = spec.and((r, q, cb) -> cb.greaterThanOrEqualTo(r.get("occurredAt"), fromInstant));
        }
        if (toInstant != null) {
            spec = spec.and((r, q, cb) -> cb.lessThanOrEqualTo(r.get("occurredAt"), toInstant));
        }

        Page<AuditLogEntry> result = repository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt")));
        return new AuditLogPageResponse(result.map(AuditLogQueryService::toResponse).getContent(),
                page, size, result.getTotalElements());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static Instant parse(String name, String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidAuditQueryException(name + " must be an ISO-8601 instant, e.g. 2026-01-02T10:00:00Z");
        }
    }

    private static AuditLogEntryResponse toResponse(AuditLogEntry e) {
        return new AuditLogEntryResponse(e.getId(), e.getOccurredAt(), e.getUsername(), e.getAction(),
                e.getOutcome().name(), e.getTargetType(), e.getTargetId(), e.getDetails());
    }
}
