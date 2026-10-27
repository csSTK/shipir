package dev.bethge.shipir.docker.dto;

import java.util.List;

/**
 * Disk usage of one Docker environment as Docker reports it ({@code GET /system/df}). A field is null when the
 * daemon did not report it (e.g. no size for a volume whose usage was not computed). {@code largest} holds at most 10
 * items across all categories, biggest first; never null.
 */
public record DiskUsageDto(CategoryUsage images, CategoryUsage containers, CategoryUsage volumes, CategoryUsage buildCache,
                           List<LargestItem> largest) {

    /** One of the biggest objects; {@code name} is a safe display string (no labels, commands or other free text). */
    public record LargestItem(Category category, String name, long size) {

        public enum Category { IMAGE, CONTAINER, VOLUME, BUILD_CACHE }
    }

    /**
     * {@code totalSize} is the bytes the category occupies, {@code reclaimableSize} the part that a prune would free
     * (unused images, stopped containers' writable layers, unreferenced volumes, unused build cache).
     */
    public record CategoryUsage(Integer count, Long totalSize, Long reclaimableSize) {

        public static final CategoryUsage UNREPORTED = new CategoryUsage(null, null, null);
    }
}
