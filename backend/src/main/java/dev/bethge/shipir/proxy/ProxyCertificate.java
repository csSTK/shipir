package dev.bethge.shipir.proxy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An uploaded TLS certificate of one host. The private key exists only as ciphertext; domains come from SAN/CN. */
@Entity
@Table(name = "proxy_certificates")
public class ProxyCertificate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "cert_pem", nullable = false)
    private String certPem;

    @Column(name = "key_encrypted", nullable = false)
    private byte[] keyEncrypted;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> domains;

    @Column(name = "not_after", nullable = false)
    private Instant notAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProxyCertificate() {
    }

    public static ProxyCertificate create(UUID hostId, String name, String certPem, byte[] keyEncrypted,
                                          List<String> domains, Instant notAfter) {
        ProxyCertificate c = new ProxyCertificate();
        c.hostId = hostId;
        c.name = name;
        c.certPem = certPem;
        c.keyEncrypted = keyEncrypted;
        c.domains = List.copyOf(domains);
        c.notAfter = notAfter;
        c.createdAt = Instant.now();
        return c;
    }

    /** Renewal: new chain, key and the values read from it; name and host stay. */
    public void replace(String certPem, byte[] keyEncrypted, List<String> domains, Instant notAfter) {
        this.certPem = certPem;
        this.keyEncrypted = keyEncrypted;
        this.domains = List.copyOf(domains);
        this.notAfter = notAfter;
    }

    public UUID getId() { return id; }
    public UUID getHostId() { return hostId; }
    public String getName() { return name; }
    public String getCertPem() { return certPem; }
    public byte[] getKeyEncrypted() { return keyEncrypted; }
    public List<String> getDomains() { return domains; }
    public Instant getNotAfter() { return notAfter; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "ProxyCertificate[id=" + id + ", hostId=" + hostId + ", name=" + name + "]";
    }
}
