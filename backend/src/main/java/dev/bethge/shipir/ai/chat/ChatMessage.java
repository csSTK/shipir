package dev.bethge.shipir.ai.chat;

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
import java.util.UUID;

/** One message of a session, ordered by {@code seq}. {@code toolCalls} is the raw JSON array of the model's requests. */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private ChatRole role;

    @Column(columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "jsonb")
    private String toolCalls;

    @Column(name = "tool_call_id")
    private String toolCallId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ChatMessageStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatMessage() {
    }

    ChatMessage(UUID sessionId, int seq, ChatRole role, String content, String toolCalls,
                String toolCallId, ChatMessageStatus status, Instant now) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
        this.status = status;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public int getSeq() { return seq; }
    public ChatRole getRole() { return role; }
    public String getContent() { return content; }
    public String getToolCalls() { return toolCalls; }
    public String getToolCallId() { return toolCallId; }
    public ChatMessageStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public void setStatus(ChatMessageStatus status) {
        this.status = status;
    }

    /** Settles a pending action: new status and the outcome that replaces the display text. */
    void resolve(ChatMessageStatus status, String outcome) {
        this.status = status;
        this.content = outcome;
    }
}
