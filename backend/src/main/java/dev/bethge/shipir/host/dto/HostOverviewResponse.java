package dev.bethge.shipir.host.dto;

import dev.bethge.shipir.host.ConnectionType;

import java.util.List;
import java.util.UUID;

/**
 * Live state of one environment. For {@code DOWN} environments {@code error} holds a stable code and all
 * statistics are {@code null}; for {@code UP} environments {@code error} is {@code null}. {@code tags} is sorted.
 */
public record HostOverviewResponse(UUID id, String name, ConnectionType connectionType, String endpoint,
                                   Status status, String error, String dockerVersion, Integer cpus,
                                   Long memoryBytes, Containers containers, Integer images, Integer volumes,
                                   List<String> tags) {

    public enum Status { UP, DOWN }

    /** Without tags. */
    public HostOverviewResponse(UUID id, String name, ConnectionType connectionType, String endpoint,
                                Status status, String error, String dockerVersion, Integer cpus,
                                Long memoryBytes, Containers containers, Integer images, Integer volumes) {
        this(id, name, connectionType, endpoint, status, error, dockerVersion, cpus, memoryBytes, containers,
                images, volumes, List.of());
    }

    public record Containers(int running, int stopped, int healthy, int unhealthy) {
    }
}
