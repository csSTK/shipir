package dev.bethge.shipir.docker.dto;

import java.time.Instant;
import java.util.List;

/**
 * One container (any state). {@code health} is {@code healthy}, {@code unhealthy}, {@code starting} or null
 * without healthcheck; {@code ports} look like {@code 8080:80/tcp} or {@code 80/tcp}; the compose fields
 * are null for containers not started by Compose.
 */
public record ContainerDto(
        String id,
        String name,
        String image,
        String state,
        String status,
        String health,
        Instant created,
        List<String> ports,
        String composeProject,
        String composeService) {
}
