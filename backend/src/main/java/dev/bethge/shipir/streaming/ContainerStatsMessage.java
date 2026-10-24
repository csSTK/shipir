package dev.bethge.shipir.streaming;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Frame of {@code /topic/hosts/{hostId}/stats}, one per interval with a sample for every running container the
 * subscriber may see. {@code code} is only set when the host could not be sampled (a DockerErrorCode name,
 * NOT_FOUND = host gone); {@code samples} is then empty. {@code cpu} is a percentage (can exceed 100), memory
 * in bytes without page cache, net and block values are cumulative counters. {@code seq} counts frames per host.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContainerStatsMessage(long seq, List<Sample> samples, String code) {

    public record Sample(String containerId, double cpu, long memUsed, long memLimit, long netRx, long netTx,
                         long blkRead, long blkWrite) {
    }
}
