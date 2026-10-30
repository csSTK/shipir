package dev.bethge.shipir.proxy;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** The managed reverse proxy of one Docker host. DNS provider credentials exist only as ciphertext plus a display hint. */
@Entity
@Table(name = "proxy_instances")
public class ProxyInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "deployment_id")
    private UUID deploymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ProxyType type;

    @Column(name = "acme_email", length = 255)
    private String acmeEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_cert_mode", nullable = false, length = 20)
    private CertMode defaultCertMode;

    @Column(name = "dns_provider", length = 50)
    private String dnsProvider;

    @Column(name = "dns_credentials_encrypted")
    private byte[] dnsCredentialsEncrypted;

    @Column(name = "dns_credentials_hint", length = 255)
    private String dnsCredentialsHint;

    @Column(name = "custom_config", nullable = false)
    private String customConfig = "";

    @Column(name = "custom_config_updated_at")
    private Instant customConfigUpdatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProxyInstance() {
    }

    public static ProxyInstance create(UUID hostId, ProxyType type, String acmeEmail, CertMode defaultCertMode) {
        ProxyInstance p = new ProxyInstance();
        p.hostId = hostId;
        p.type = type;
        p.acmeEmail = acmeEmail;
        p.defaultCertMode = defaultCertMode;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        return p;
    }

    public void linkDeployment(UUID deploymentId) {
        this.deploymentId = deploymentId;
        touch();
    }

    public void configure(String acmeEmail, CertMode defaultCertMode) {
        this.acmeEmail = acmeEmail;
        this.defaultCertMode = defaultCertMode;
        touch();
    }

    /** Ciphertext comes from {@code SecretCipher} in the service layer; null clears the credentials. */
    public void setDnsCredentials(String provider, byte[] encrypted, String hint) {
        this.dnsProvider = provider;
        this.dnsCredentialsEncrypted = encrypted;
        this.dnsCredentialsHint = hint;
        touch();
    }

    public void setCustomConfig(String customConfig) {
        this.customConfig = customConfig == null ? "" : customConfig;
        this.customConfigUpdatedAt = Instant.now();
        touch();
    }

    /** Puts back a custom config (and its timestamp) after a change that could not be applied. */
    public void revertCustomConfig(String customConfig, Instant updatedAt) {
        this.customConfig = customConfig == null ? "" : customConfig;
        this.customConfigUpdatedAt = updatedAt;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getHostId() { return hostId; }
    public UUID getDeploymentId() { return deploymentId; }
    public ProxyType getType() { return type; }
    public String getAcmeEmail() { return acmeEmail; }
    public CertMode getDefaultCertMode() { return defaultCertMode; }
    public String getDnsProvider() { return dnsProvider; }
    public String getDnsCredentialsHint() { return dnsCredentialsHint; }
    public byte[] getDnsCredentialsEncrypted() { return dnsCredentialsEncrypted; }
    public String getCustomConfig() { return customConfig; }
    public Instant getCustomConfigUpdatedAt() { return customConfigUpdatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "ProxyInstance[id=" + id + ", hostId=" + hostId + ", type=" + type + "]";
    }
}
