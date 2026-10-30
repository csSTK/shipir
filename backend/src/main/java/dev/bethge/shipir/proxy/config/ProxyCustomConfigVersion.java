package dev.bethge.shipir.proxy.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A backup of the custom proxy config: the content that a later write replaced (see V40). */
@Entity
@Table(name = "proxy_custom_config_versions")
class ProxyCustomConfigVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "proxy_instance_id", nullable = false, updatable = false)
    private UUID proxyInstanceId;

    @Column(nullable = false, updatable = false)
    private String content;

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProxyCustomConfigVersion() {
    }

    ProxyCustomConfigVersion(UUID proxyInstanceId, String content, String createdBy) {
        this.proxyInstanceId = proxyInstanceId;
        this.content = content;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    UUID getId() { return id; }
    UUID getProxyInstanceId() { return proxyInstanceId; }
    String getContent() { return content; }
    String getCreatedBy() { return createdBy; }
    Instant getCreatedAt() { return createdAt; }
}
