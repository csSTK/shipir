package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.BuildSpec;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.RegistryLogin;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    /**
     * Reads a build context (tar, optionally gzip) and refuses hostile archives early: absolute or {@code ..} entry names,
     * links leaving the root, writes through links, devices and FIFOs, over-long or too deep names, and more than
     * {@code maxBytes} of content (also when a gzip expands). Throws {@code DockerConnectionException} INVALID_ARCHIVE or
     * SIZE_LIMIT_EXCEEDED. The caller closes {@code tar}.
     */
    void validateBuildContext(InputStream tar, long maxBytes);

    /**
     * Builds an image from a tar build context and blocks until done. {@code context} is opened once for validation
     * (see {@link #validateBuildContext}) and once to send it, so it must be repeatable. {@code auths} are registry
     * logins for base images, sent to the daemon for this build only. {@code onOutput} gets the build log line by line
     * (bounded length and count). Throws {@code DockerConnectionException} with INVALID_ARCHIVE, SIZE_LIMIT_EXCEEDED,
     * BUILD_FAILED, TIMEOUT or a connection code; the daemon's error text is never forwarded.
     */
    void build(UUID hostId, DockerEndpoint endpoint, Supplier<InputStream> context, BuildSpec spec,
               List<RegistryLogin> auths, Consumer<String> onOutput);

    /** Adds {@code repository:tag} to the image. Throws {@code DockerConnectionException} with NOT_FOUND or a connection code. */
    void tag(UUID hostId, DockerEndpoint endpoint, String imageId, String repository, String tag);

    /**
     * Pushes {@code reference} ({@code repository:tag}) and blocks until done. {@code auth} (may be null) is sent to the
     * daemon for this push only. Throws {@code DockerConnectionException} with NOT_FOUND, PUSH_FAILED, TIMEOUT or a connection code.
     */
    void push(UUID hostId, DockerEndpoint endpoint, String reference, RegistryLogin auth, Consumer<String> onProgress);
}
