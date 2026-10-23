package dev.bethge.shipir.docker.dto;

import java.time.Instant;
import java.util.List;

/** One image. {@code repoTags} is empty for dangling images; {@code inUse} = any container (any state) uses it. */
public record ImageDto(String id, List<String> repoTags, Long size, Instant created, boolean inUse) {
}
