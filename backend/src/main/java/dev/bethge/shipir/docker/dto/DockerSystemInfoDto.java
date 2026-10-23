package dev.bethge.shipir.docker.dto;

/** Overview numbers of one Docker environment. */
public record DockerSystemInfoDto(
        String dockerVersion,
        int cpus,
        long memoryBytes,
        int containersRunning,
        int containersStopped,
        int containersHealthy,
        int containersUnhealthy,
        int images,
        int volumes) {
}
