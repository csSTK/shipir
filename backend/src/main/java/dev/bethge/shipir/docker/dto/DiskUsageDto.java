package dev.bethge.shipir.docker.dto;

/**
 * Disk usage of one Docker environment as Docker reports it ({@code GET /system/df}). A field is null when the
 * daemon did not report it (e.g. no size for a volume whose usage was not computed).
 */
public record DiskUsageDto(CategoryUsage images, CategoryUsage containers, CategoryUsage volumes, CategoryUsage buildCache) {

    /**
     * {@code totalSize} is the bytes the category occupies, {@code reclaimableSize} the part that a prune would free
     * (unused images, stopped containers' writable layers, unreferenced volumes, unused build cache).
     */
    public record CategoryUsage(Integer count, Long totalSize, Long reclaimableSize) {

        public static final CategoryUsage UNREPORTED = new CategoryUsage(null, null, null);
    }
}
