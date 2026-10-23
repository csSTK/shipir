package dev.bethge.shipir.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A one-time password reset code. Only the SHA-256 hex hash is stored, never the code itself. */
@Entity
@Table(name = "password_reset_codes")
class PasswordResetCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false, length = 64, updatable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PasswordResetCode() {
    }

    PasswordResetCode(UUID userId, String codeHash, Instant expiresAt, Instant createdAt) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    String getCodeHash() {
        return codeHash;
    }

    int getFailedAttempts() {
        return failedAttempts;
    }

    Instant getUsedAt() {
        return usedAt;
    }

    void recordFailedAttempt() {
        failedAttempts++;
    }

    void markUsed(Instant at) {
        this.usedAt = at;
    }
}
