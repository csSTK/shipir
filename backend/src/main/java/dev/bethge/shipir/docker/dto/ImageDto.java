package dev.bethge.shipir.docker.dto;

import java.time.Instant;
import java.util.List;

/**
 * One image. {@code repoTags} is empty for dangling images; {@code inUse} = any container (any state) uses it;
 * {@code repoDigests} are {@code repository@sha256:...} entries (empty for locally built images);
 * {@code updateAvailable} is filled by the host layer from the last registry check.
 */
public record ImageDto(String id, List<String> repoTags, Long size, Instant created, boolean inUse,
                       List<String> repoDigests, boolean updateAvailable) {

    public ImageDto(String id, List<String> repoTags, Long size, Instant created, boolean inUse) {
        this(id, repoTags, size, created, inUse, List.of(), false);
    }

    public ImageDto withUpdateAvailable(boolean updateAvailable) {
        return new ImageDto(id, repoTags, size, created, inUse, repoDigests, updateAvailable);
    }
}
