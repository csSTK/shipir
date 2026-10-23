package dev.bethge.shipir.host;

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

/** A Docker environment. TLS material is stored as ciphertext only; see {@code SecretCipher}. */
@Entity
@Table(name = "docker_hosts")
public class DockerHost {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_type", nullable = false, length = 20)
    private ConnectionType connectionType;

    private String host;

    private Integer port;

    @Column(name = "tls_ca_encrypted")
    private byte[] tlsCaEncrypted;

    @Column(name = "tls_cert_encrypted")
    private byte[] tlsCertEncrypted;

    @Column(name = "tls_key_encrypted")
    private byte[] tlsKeyEncrypted;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DockerHost() {
    }

    public static DockerHost local(String name, UUID ownerUserId) {
        DockerHost h = new DockerHost();
        h.name = name;
        h.connectionType = ConnectionType.LOCAL;
        h.ownerUserId = ownerUserId;
        h.createdAt = Instant.now();
        return h;
    }

    public static DockerHost tcp(String name, String host, int port, byte[] caEncrypted, byte[] certEncrypted,
                                 byte[] keyEncrypted, UUID ownerUserId) {
        DockerHost h = new DockerHost();
        h.name = name;
        h.connectionType = ConnectionType.TCP;
        h.host = host;
        h.port = port;
        h.tlsCaEncrypted = caEncrypted;
        h.tlsCertEncrypted = certEncrypted;
        h.tlsKeyEncrypted = keyEncrypted;
        h.ownerUserId = ownerUserId;
        h.createdAt = Instant.now();
        return h;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public boolean hasTls() {
        return tlsCaEncrypted != null;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ConnectionType getConnectionType() {
        return connectionType;
    }

    public String getHost() {
        return host;
    }

    public Integer getPort() {
        return port;
    }

    byte[] getTlsCaEncrypted() {
        return tlsCaEncrypted;
    }

    byte[] getTlsCertEncrypted() {
        return tlsCertEncrypted;
    }

    byte[] getTlsKeyEncrypted() {
        return tlsKeyEncrypted;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
