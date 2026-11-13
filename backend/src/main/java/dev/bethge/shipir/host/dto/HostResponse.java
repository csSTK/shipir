package dev.bethge.shipir.host.dto;

import dev.bethge.shipir.host.ConnectionType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Never carries TLS material or ciphertext; {@code tls} only says whether client certificates are stored.
 * {@code tags} is sorted; empty when the environment has none. {@code canOperate}: the caller has OPERATE on the host
 * itself (add deployment, adopt/remove projects, install template); false for a VIEW-only or deployment-only grant.
 */
public record HostResponse(UUID id, String name, ConnectionType connectionType, String host, Integer port,
                           boolean tls, Instant createdAt, List<String> tags, boolean canOperate) {
}
