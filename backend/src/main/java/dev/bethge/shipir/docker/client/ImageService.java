package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.RegistryLogin;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public interface ImageService {

    /** All images of the host with usage derived from the container list. Throws {@code DockerConnectionException}. */
    List<ImageDto> list(UUID hostId, DockerEndpoint endpoint);

    /** Removes an image, never forced. Throws {@code DockerConnectionException} with NOT_FOUND or IN_USE. */
    void removeImage(UUID hostId, DockerEndpoint endpoint, String id);

    /**
     * Images a prune removes: images no container (any state) uses; with {@code includeTagged} false only dangling
     * (untagged) ones. Size is the image size (layers shared with other images are not freed).
     */
    List<PruneCandidateDto> listPrunable(UUID hostId, DockerEndpoint endpoint, boolean includeTagged);

    /**
     * Pulls {@code reference} (e.g. {@code nginx:1.27}, {@code ghcr.io/o/app@sha256:..}) and blocks until done.
     * {@code auth} (may be null) is sent to the daemon for this pull only. {@code onProgress} gets one readable
     * line per layer status change. Throws {@code DockerConnectionException} with NOT_FOUND, PULL_FAILED or a connection code.
     */
    void pull(UUID hostId, DockerEndpoint endpoint, String reference, RegistryLogin auth, Consumer<String> onProgress);
}
