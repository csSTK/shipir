package dev.bethge.shipir.ai.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One row per provider. The API key exists only as ciphertext. */
@Entity
@Table(name = "ai_provider_configs")
class AiProviderConfig {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AiProvider provider;

    @Column(name = "base_url", nullable = false, length = 2048)
    private String baseUrl;

    @Column(nullable = false)
    private String model;

    @Column(name = "api_key_ciphertext")
    private byte[] apiKeyCiphertext;

    @Column(name = "api_key_hint", length = 32)
    private String apiKeyHint;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected AiProviderConfig() {
    }

    AiProvider getProvider() { return provider; }
    String getBaseUrl() { return baseUrl; }
    String getModel() { return model; }
    byte[] getApiKeyCiphertext() { return apiKeyCiphertext; }
    String getApiKeyHint() { return apiKeyHint; }

    boolean hasKey() {
        return apiKeyCiphertext != null;
    }

    void update(String baseUrl, String model, Instant now, UUID by) {
        this.baseUrl = baseUrl;
        this.model = model;
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
        return "AiProviderConfig[provider=" + provider + ", baseUrl=" + baseUrl + ", model=" + model + "]";
    }
}
