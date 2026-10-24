package dev.bethge.shipir.docker.dto;

import java.time.Instant;

/**
 * One resource sample of a container. CPU is a percentage of one core times the core count as the Docker CLI
 * shows it (can exceed 100), memory excludes the page cache, network and block I/O are cumulative counters.
 */
public record ContainerStatsDto(
        Instant time,
        double cpuPercent,
        long memoryUsageBytes,
        long memoryLimitBytes,
        double memoryPercent,
        long networkRxBytes,
        long networkTxBytes,
        long blockReadBytes,
        long blockWriteBytes) {
}
