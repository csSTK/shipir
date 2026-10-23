package dev.bethge.shipir.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Append-only record of one user action; {@code username} is a snapshot, {@code userId} is null when unknown. */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry {

    public enum Outcome { SUCCESS, FAILURE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false, length = 50)
    private String username;

    @Column(nullable = false, updatable = false, length = 64)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Outcome outcome;

    @Column(name = "target_type", updatable = false, length = 64)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private String targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private Map<String, Object> details;

    protected AuditLogEntry() {
    }

    AuditLogEntry(Instant occurredAt, AuditRecord r) {
        this.occurredAt = occurredAt;
        this.userId = r.userId();
        this.username = r.username();
        this.action = r.action();
        this.outcome = r.outcome();
        this.targetType = r.targetType();
        this.targetId = r.targetId();
        this.details = r.details();
    }

    public UUID getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getAction() { return action; }
    public Outcome getOutcome() { return outcome; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public Map<String, Object> getDetails() { return details; }
}
