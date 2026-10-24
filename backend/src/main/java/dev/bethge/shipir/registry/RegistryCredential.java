package dev.bethge.shipir.registry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Username and password or token for one private registry host. The secret exists only as ciphertext. */
@Entity
@Table(name = "registry_credentials")
public class RegistryCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "registry_host", nullable = false, length = 255)
    private String registryHost;

    @Column(nullable = false, length = 255)
    private String username;

    @Column(name = "secret_ciphertext", nullable = false)
    private byte[] secretCiphertext;

    @Column(name = "secret_hint", nullable = false, length = 32)
    private String secretHint;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RegistryCredential() {
    }

    RegistryCredential(String name, String registryHost, String username, byte[] secretCiphertext,
                       String secretHint, UUID createdBy, Instant now) {
        this.name = name;
        this.registryHost = registryHost;
        this.username = username;
        this.secretCiphertext = secretCiphertext;
        this.secretHint = secretHint;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(String name, String registryHost, String username, Instant now) {
        this.name = name;
        this.registryHost = registryHost;
        this.username = username;
        this.updatedAt = now;
    }

    void replaceSecret(byte[] secretCiphertext, String secretHint) {
        this.secretCiphertext = secretCiphertext;
        this.secretHint = secretHint;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getRegistryHost() { return registryHost; }
    public String getUsername() { return username; }
    public String getSecretHint() { return secretHint; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    byte[] getSecretCiphertext() { return secretCiphertext; }

    @Override
    public String toString() {
        return "RegistryCredential[id=" + id + ", name=" + name + ", registryHost=" + registryHost + "]";
    }
}
