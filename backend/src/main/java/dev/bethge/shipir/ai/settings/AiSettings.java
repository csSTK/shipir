package dev.bethge.shipir.ai.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Singleton row (id = 1, enforced by a CHECK constraint): global switch and active provider. */
@Entity
@Table(name = "ai_settings")
class AiSettings {

    static final short SINGLETON_ID = 1;

    @Id
    private short id = SINGLETON_ID;

    @Enumerated(EnumType.STRING)
    @Column(name = "active_provider", nullable = false, length = 32)
    private AiProvider activeProvider = AiProvider.OPENAI_COMPATIBLE;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected AiSettings() {
    }

    AiProvider getActiveProvider() { return activeProvider; }
    boolean isEnabled() { return enabled; }

    void update(AiProvider activeProvider, boolean enabled, Instant now, UUID by) {
        this.activeProvider = activeProvider;
        this.enabled = enabled;
        this.updatedAt = now;
        this.updatedBy = by;
    }

    @Override
    public String toString() {
        return "AiSettings[activeProvider=" + activeProvider + ", enabled=" + enabled + "]";
    }
}
