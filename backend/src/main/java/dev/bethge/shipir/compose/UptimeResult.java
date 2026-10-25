package dev.bethge.shipir.compose;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One probe result. {@code errorCode} is a fixed code, never exception text. */
@Entity
@Table(name = "uptime_results")
public class UptimeResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "check_id", nullable = false, updatable = false)
    private UUID checkId;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    @Column(nullable = false, updatable = false)
    private boolean ok;

    @Column(name = "status_code", updatable = false)
    private Integer statusCode;

    @Column(name = "latency_ms", updatable = false)
    private Integer latencyMs;

    @Column(name = "error_code", length = 40, updatable = false)
    private String errorCode;

    protected UptimeResult() {
    }

    UptimeResult(UUID checkId, Instant checkedAt, boolean ok, Integer statusCode, Integer latencyMs, String errorCode) {
        this.checkId = checkId;
        this.checkedAt = checkedAt;
        this.ok = ok;
        this.statusCode = statusCode;
        this.latencyMs = latencyMs;
        this.errorCode = errorCode;
    }

    public UUID getCheckId() { return checkId; }
    public Instant getCheckedAt() { return checkedAt; }
    public boolean isOk() { return ok; }
    public Integer getStatusCode() { return statusCode; }
    public Integer getLatencyMs() { return latencyMs; }
    public String getErrorCode() { return errorCode; }
}
