package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.ImageDto;

import java.util.List;
import java.util.UUID;

public interface ImageService {

    /** All images of the host with usage derived from the container list. Throws {@code DockerConnectionException}. */
    List<ImageDto> list(UUID hostId, DockerEndpoint endpoint);

    /** Removes an image, never forced. Throws {@code DockerConnectionException} with NOT_FOUND or IN_USE. */
    void removeImage(UUID hostId, DockerEndpoint endpoint, String id);
}
