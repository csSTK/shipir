package dev.bethge.shipir.docker.dto;

import java.time.Instant;
import java.util.List;

/**
 * One container (any state). {@code health} is {@code healthy}, {@code unhealthy}, {@code starting} or null
 * without healthcheck; {@code ports} look like {@code 8080:80/tcp} or {@code 80/tcp}; the compose fields
 * are null for containers not started by Compose. {@code imageId} is the ID of the image the container was created
 * from (may be null); when its tag has since moved to another image, Docker reports {@code image} as that ID.
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
        String composeService,
        String imageId) {

    public ContainerDto(String id, String name, String image, String state, String status, String health,
                        Instant created, List<String> ports, String composeProject, String composeService) {
        this(id, name, image, state, status, health, created, ports, composeProject, composeService, null);
    }

    /** True when the container no longer runs the image its tag points to: Docker then shows the image ID instead. */
    public boolean runsUntaggedImage() {
        if (imageId == null || image == null || image.isBlank()) {
            return false;
        }
        String id = imageId.startsWith("sha256:") ? imageId.substring(7) : imageId;
        String shown = image.startsWith("sha256:") ? image.substring(7) : image;
        return shown.length() >= 12 && id.startsWith(shown);
    }
}
