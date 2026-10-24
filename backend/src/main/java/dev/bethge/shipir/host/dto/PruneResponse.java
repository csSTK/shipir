package dev.bethge.shipir.host.dto;

/**
 * Outcome of a prune. {@code skipped} = candidates that became in use or vanished meanwhile (left untouched).
 * {@code reclaimedBytes} sums the known sizes of removed items (image sizes may include layers shared with other images).
 */
public record PruneResponse(Result containers, Result images, Result volumes, Result networks, long reclaimedBytes) {

    public record Result(int removed, int skipped, long reclaimedBytes) {
        public static final Result NONE = new Result(0, 0, 0);
    }
}
