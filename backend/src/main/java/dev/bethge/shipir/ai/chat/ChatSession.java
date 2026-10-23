package dev.bethge.shipir.ai.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One conversation. {@code sharedWithAdmin} exposes it read-only to ADMIN users. */
@Entity
@Table(name = "chat_sessions")
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String title;

    @Column(name = "shared_with_admin", nullable = false)
    private boolean sharedWithAdmin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChatSession() {
    }

    ChatSession(UUID userId, String title, Instant now) {
        this.userId = userId;
        this.title = title;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTitle() { return title; }
    public boolean isSharedWithAdmin() { return sharedWithAdmin; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    void rename(String title, Instant now) {
        this.title = title;
        this.updatedAt = now;
    }

    void setSharedWithAdmin(boolean shared, Instant now) {
        this.sharedWithAdmin = shared;
        this.updatedAt = now;
    }

    void touch(Instant now) {
        this.updatedAt = now;
    }
}
