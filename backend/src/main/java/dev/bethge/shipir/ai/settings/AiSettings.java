package dev.bethge.shipir.ai.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Singleton row (id = 1, enforced by a CHECK constraint). The API key exists only as ciphertext. */
@Entity
@Table(name = "ai_settings")
class AiSettings {

    static final short SINGLETON_ID = 1;

    @Id
    private short id = SINGLETON_ID;

    @Column(name = "base_url", nullable = false, length = 2048)
    private String baseUrl;

    @Column(nullable = false)
    private String model;

    @Column(name = "api_key_ciphertext")
    private byte[] apiKeyCiphertext;

    @Column(name = "api_key_hint", length = 32)
    private String apiKeyHint;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected AiSettings() {
    }

    String getBaseUrl() { return baseUrl; }
    String getModel() { return model; }
    byte[] getApiKeyCiphertext() { return apiKeyCiphertext; }
    String getApiKeyHint() { return apiKeyHint; }
    boolean isEnabled() { return enabled; }

    boolean hasKey() {
        return apiKeyCiphertext != null;
    }

    void update(String baseUrl, String model, boolean enabled, Instant now, UUID by) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.enabled = enabled;
        this.updatedAt = now;
        this.updatedBy = by;
    }

    void replaceKey(byte[] ciphertext, String hint) {
        this.apiKeyCiphertext = ciphertext;
        this.apiKeyHint = hint;
    }

    void clearKey() {
        this.apiKeyCiphertext = null;
        this.apiKeyHint = null;
    }

    @Override
    public String toString() {
        return "AiSettings[baseUrl=" + baseUrl + ", model=" + model + ", enabled=" + enabled + "]";
    }
}
