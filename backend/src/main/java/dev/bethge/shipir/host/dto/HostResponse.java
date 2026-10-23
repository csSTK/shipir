package dev.bethge.shipir.host.dto;

import dev.bethge.shipir.host.ConnectionType;

import java.time.Instant;
import java.util.UUID;

/** Never carries TLS material or ciphertext; {@code tls} only says whether client certificates are stored. */
public record HostResponse(UUID id, String name, ConnectionType connectionType, String host, Integer port,
                           boolean tls, Instant createdAt) {
}
