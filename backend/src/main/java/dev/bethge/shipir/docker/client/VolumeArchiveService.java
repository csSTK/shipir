package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Backup and restore of a named volume as a tar archive, through a short-lived helper container that only has the
 * volume mounted (no network, not privileged, never started: the daemon's archive API reads and writes the mount).
 */
public interface VolumeArchiveService {

    /**
     * Opens a tar of the volume's contents (read-only mount). Closing the returned stream removes the helper
     * container; reading past {@code maxBytes} fails with SIZE_LIMIT_EXCEEDED. Throws {@code DockerConnectionException}:
     * INVALID_VOLUME_NAME, NOT_FOUND (volume), PULL_FAILED (helper image).
     */
    InputStream backup(UUID hostId, DockerEndpoint endpoint, String volumeName, String helperImage, long maxBytes);

    /**
     * Extracts a tar into an existing volume. The archive is validated first (no entry may leave the volume root),
     * then streamed. Throws {@code DockerConnectionException}: INVALID_VOLUME_NAME, INVALID_ARCHIVE,
     * SIZE_LIMIT_EXCEEDED, NOT_FOUND (volume does not exist; it is never created implicitly), IN_USE (a container
     * mounts the volume and {@code allowInUse} is false). The helper container is always removed.
     */
    void restore(UUID hostId, DockerEndpoint endpoint, String volumeName, String helperImage, ArchiveSource tar,
                 long maxBytes, boolean allowInUse);

    /** A re-openable archive: opened once to validate and once to stream. */
    @FunctionalInterface
    interface ArchiveSource {
        InputStream open() throws IOException;
    }
}
