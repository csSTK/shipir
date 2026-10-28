package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.ContainerFileEntry;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Reads and writes files inside a container through the Docker archive API (works on stopped
 * containers and images without a shell; the only exec use is the directory-list fallback). All methods throw {@code DockerConnectionException}: NOT_FOUND
 * (unknown container or path), INVALID_PATH (not absolute, NUL, or not a directory where required),
 * SIZE_LIMIT_EXCEEDED (enforced while streaming).
 */
public interface ContainerFileService {

    /**
     * Direct children of the directory {@code path}, directories first then by name. A symlink to a directory is
     * followed once. The daemon sends the whole subtree as tar, so {@code maxScanBytes} caps the bytes read;
     * past that cap the listing falls back to a single-directory exec ({@code find}, running containers only) and
     * throws DIRECTORY_TOO_LARGE if that is not possible or the directory has too many entries.
     */
    List<ContainerFileEntry> list(UUID hostId, DockerEndpoint endpoint, String containerId, String path, long maxScanBytes);

    /**
     * Tar stream of the file or directory at {@code path}. Reading more than {@code maxBytes} throws
     * SIZE_LIMIT_EXCEEDED from {@code read}. The caller closes the stream.
     */
    InputStream download(UUID hostId, DockerEndpoint endpoint, String containerId, String path, long maxBytes);

    /**
     * Extracts {@code tar} into the existing directory {@code directory}. More than {@code maxBytes} of tar
     * aborts the upload with SIZE_LIMIT_EXCEEDED; the daemon extracts while receiving, so files before the cut may remain. Does not close {@code tar}.
     */
    void upload(UUID hostId, DockerEndpoint endpoint, String containerId, String directory, InputStream tar, long maxBytes);
}
