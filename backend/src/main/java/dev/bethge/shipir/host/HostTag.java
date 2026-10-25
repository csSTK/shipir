package dev.bethge.shipir.host;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One label on an environment. The host is referenced by id only; the tag set is managed by {@link HostTagService}. */
@Entity
@Table(name = "host_tags")
public class HostTag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(nullable = false, length = 24, updatable = false)
    private String tag;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected HostTag() {
    }

    HostTag(UUID hostId, String tag) {
        this.hostId = hostId;
        this.tag = tag;
        this.createdAt = Instant.now();
    }

    UUID getHostId() {
        return hostId;
    }

    String getTag() {
        return tag;
    }
}
