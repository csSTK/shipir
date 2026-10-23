package dev.bethge.shipir.host;

import dev.bethge.shipir.host.dto.HostOverviewResponse.Status;

import java.time.Instant;
import java.util.UUID;

/** Published when a host's observed status flips between UP and DOWN. {@code errorCode} is null when UP. */
public record HostStatusChangedEvent(UUID hostId, String hostName, Status previous, Status current,
                                     String errorCode, Instant occurredAt) {
}
