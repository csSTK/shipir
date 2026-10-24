package dev.bethge.shipir.auth.oidc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Singleton row (id = 1, CHECK constraint). The client secret exists only as ciphertext. */
@Entity
@Table(name = "oidc_settings")
class OidcSettings {

    static final short SINGLETON_ID = 1;

    @Id
    private short id = SINGLETON_ID;

    @Column(name = "issuer_url", length = 2048)
    private String issuerUrl;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "client_secret_ciphertext")
    private byte[] clientSecretCiphertext;

    @Column(name = "client_secret_hint", length = 32)
    private String clientSecretHint;

    @Column(nullable = false, length = 512)
    private String scopes;

    @Column(name = "groups_claim")
    private String groupsClaim;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "role_mapping", nullable = false, columnDefinition = "jsonb")
    private String roleMapping;

    @Column(name = "auto_provision", nullable = false)
    private boolean autoProvision;

    @Column(name = "local_login_enabled", nullable = false)
    private boolean localLoginEnabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected OidcSettings() {
    }

    /** Usable for sign-in: provider, client id and a stored secret. */
    boolean isEnabled() {
        return issuerUrl != null && !issuerUrl.isBlank() && clientId != null && !clientId.isBlank()
                && clientSecretCiphertext != null;
    }

    String getIssuerUrl() { return issuerUrl; }
    String getClientId() { return clientId; }
    byte[] getClientSecretCiphertext() { return clientSecretCiphertext; }
    String getClientSecretHint() { return clientSecretHint; }
    String getScopes() { return scopes; }
    String getGroupsClaim() { return groupsClaim; }
    String getRoleMapping() { return roleMapping; }
    boolean isAutoProvision() { return autoProvision; }
    boolean isLocalLoginEnabled() { return localLoginEnabled; }

    void replaceSecret(byte[] ciphertext, String hint) {
        this.clientSecretCiphertext = ciphertext;
        this.clientSecretHint = hint;
    }

    void clearSecret() {
        this.clientSecretCiphertext = null;
        this.clientSecretHint = null;
    }

    void update(String issuerUrl, String clientId, String scopes, String groupsClaim, String roleMapping,
                boolean autoProvision, boolean localLoginEnabled, Instant now, UUID by) {
        this.issuerUrl = issuerUrl;
        this.clientId = clientId;
        this.scopes = scopes;
        this.groupsClaim = groupsClaim;
        this.roleMapping = roleMapping;
        this.autoProvision = autoProvision;
        this.localLoginEnabled = localLoginEnabled;
        this.updatedAt = now;
        this.updatedBy = by;
    }

    @Override
    public String toString() {
        return "OidcSettings[issuerUrl=" + issuerUrl + ", clientId=" + clientId + ", secret=<hidden>]";
    }
}
