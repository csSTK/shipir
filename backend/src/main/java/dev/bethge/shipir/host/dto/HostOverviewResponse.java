package dev.bethge.shipir.host.dto;

import dev.bethge.shipir.host.ConnectionType;

import java.util.UUID;

/**
 * Live state of one environment. For {@code DOWN} environments {@code error} holds a stable code and all
 * statistics are {@code null}; for {@code UP} environments {@code error} is {@code null}.
 */
public record HostOverviewResponse(UUID id, String name, ConnectionType connectionType, String endpoint,
                                   Status status, String error, String dockerVersion, Integer cpus,
                                   Long memoryBytes, Containers containers, Integer images, Integer volumes) {

    public enum Status { UP, DOWN }

    public record Containers(int running, int stopped, int healthy, int unhealthy) {
    }
}
