package dev.bethge.shipir.host.dto;

import dev.bethge.shipir.docker.dto.PruneCandidateDto;

import java.util.List;

/**
 * What a prune would remove right now, per category. {@code sizeBytes} sums the known item sizes (volumes and
 * networks report none). {@code managedVolumesExcluded} = unused volumes of managed deployments left out.
 */
public record PrunePreviewResponse(Category containers, Category images, Category volumes, Category networks,
                                   int managedVolumesExcluded) {

    public record Category(int count, long sizeBytes, List<PruneCandidateDto> items) {
        public static Category of(List<PruneCandidateDto> items) {
            return new Category(items.size(), items.stream().mapToLong(i -> i.sizeBytes() == null ? 0 : i.sizeBytes()).sum(),
                    items);
        }
    }
}
