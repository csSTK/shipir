package dev.bethge.shipir.compose;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** An HTTP uptime check of a managed deployment, with the state the flapping dedupe works on. */
@Entity
@Table(name = "uptime_checks")
public class UptimeCheck {

    public enum State { UNKNOWN, UP, DOWN }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "deployment_id", nullable = false, updatable = false)
    private UUID deploymentId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 2048)
    private String url;

    /** Null = any 2xx is up. */
    @Column(name = "expected_status")
    private Integer expectedStatus;

    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "last_status", nullable = false, length = 10)
    private State lastStatus = State.UNKNOWN;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_change_at")
    private Instant lastChangeAt;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    protected UptimeCheck() {
    }

    UptimeCheck(UUID deploymentId, String name, String url, Integer expectedStatus, int intervalSeconds,
                int timeoutSeconds, boolean enabled, Instant createdAt) {
        this.deploymentId = deploymentId;
        this.createdAt = createdAt;
        update(name, url, expectedStatus, intervalSeconds, timeoutSeconds, enabled);
    }

    void update(String name, String url, Integer expectedStatus, int intervalSeconds, int timeoutSeconds, boolean enabled) {
        if (!url.equals(this.url)) {
            // a new target starts from scratch: the old state says nothing about it
            this.lastStatus = State.UNKNOWN;
            this.lastChangeAt = null;
            this.lastCheckedAt = null;
            this.consecutiveFailures = 0;
        }
        this.name = name;
        this.url = url;
        this.expectedStatus = expectedStatus;
        this.intervalSeconds = intervalSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.enabled = enabled;
    }

    /**
     * Applies one probe result. DOWN needs {@code failureThreshold} consecutive failures, UP needs one success. The first
     * success (state UNKNOWN to UP) is a state change without notification.
     *
     * @return the new state if it changed and is worth a notification, else null
     */
    State apply(boolean ok, Instant at, int failureThreshold) {
        this.lastCheckedAt = at;
        if (ok) {
            consecutiveFailures = 0;
            if (lastStatus == State.UP) {
                return null;
            }
            State previous = lastStatus;
            lastStatus = State.UP;
            lastChangeAt = at;
            return previous == State.DOWN ? State.UP : null;
        }
        consecutiveFailures++;
        if (lastStatus != State.DOWN && consecutiveFailures >= failureThreshold) {
            lastStatus = State.DOWN;
            lastChangeAt = at;
            return State.DOWN;
        }
        return null;
    }

    public UUID getId() { return id; }
    public UUID getDeploymentId() { return deploymentId; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public Integer getExpectedStatus() { return expectedStatus; }
    public int getIntervalSeconds() { return intervalSeconds; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public State getLastStatus() { return lastStatus; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public Instant getLastChangeAt() { return lastChangeAt; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
}
