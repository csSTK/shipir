package dev.bethge.shipir.host.dto;

import java.time.Instant;
import java.util.List;

/**
 * Resource usage history of the containers of one host. Every series holds at most {@code maxPoints} points, one per
 * {@code bucketSeconds} window: CPU and memory are bucket averages, the network and block I/O counters are the
 * bucket maximum (the latest counter value); a decrease between two buckets means the container restarted (counter
 * reset). At most {@code shipir.stats.max-series} series are returned, those with the most recent samples first;
 * {@code seriesTotal} is the number of visible containers with samples in the range and {@code truncated} is true
 * when some were left out.
 */
public record StatsHistoryResponse(String range, Instant from, Instant to, int bucketSeconds, int seriesTotal,
                                   boolean truncated, List<Series> series) {

    public record Series(String containerId, List<Point> points) {
    }

    public record Point(Instant time, double cpuPercent, long memoryBytes, long networkRxBytes,
                        long networkTxBytes, long blockReadBytes, long blockWriteBytes) {
    }
}
