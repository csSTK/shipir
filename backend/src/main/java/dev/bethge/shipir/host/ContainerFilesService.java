package dev.bethge.shipir.host;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.ViewerErrorCodes;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.dto.ContainerFileEntry;
import dev.bethge.shipir.host.dto.ContainerFileDownload;
import dev.bethge.shipir.permission.CurrentAccess;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Browse, download and upload files inside a container (ADMIN only, enforced in SecurityConfig). On top of that the
 * host-level permission model applies: 404 when the host is not visible, 403 without host-level OPERATE.
 * File contents never reach the audit log or the logs, only the path.
 */
@Service
@EnableConfigurationProperties(ContainerFilesProperties.class)
public class ContainerFilesService {

    private static final Pattern RESOURCE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private static final int MAX_PATH_LENGTH = 4096;
    private static final int TAR_BLOCK = 512;
    /** Header (possibly pax), padding and end blocks around the file content. */
    private static final long TAR_OVERHEAD = 64 * 1024;

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final ContainerFileService files;
    private final CurrentAccess access;
    private final ContainerFilesProperties properties;

    ContainerFilesService(DockerHostRepository hosts, HostEndpointResolver endpoints, ContainerFileService files,
                          CurrentAccess access, ContainerFilesProperties properties) {
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.files = files;
        this.access = access;
        this.properties = properties;
    }

    public List<ContainerFileEntry> list(UUID hostId, String containerId, String path) {
        requireOperate(hostId);
        validateContainerId(containerId);
        String p = validatePath(path);
        return call(hostId, (id, endpoint) ->
                files.list(id, endpoint, containerId, p, properties.maxScanBytes().toBytes()));
    }

    /**
     * Opens a download. A single regular file is returned as its raw bytes (exact size known up front, so an
     * oversize file is a clean 413); a directory (or anything else) as tar, where the limit aborts mid-stream.
     */
    @Auditable(action = "CONTAINER_FILE_DOWNLOAD", targetType = "CONTAINER", targetId = "#containerId",
            details = "{'path': #path}")
    public ContainerFileDownload download(UUID hostId, String containerId, String path) {
        requireOperate(hostId);
        validateContainerId(containerId);
        String p = validatePath(path);
        long max = properties.maxBytes().toBytes();
        InputStream tar = call(hostId, (id, endpoint) -> files.download(id, endpoint, containerId, p, max));
        try {
            return openDownload(tar, p, max);
        } catch (RuntimeException | IOException e) {
            closeQuietly(tar);
            if (e instanceof DockerConnectionException d) {
                throw translate(d);
            }
            if (e instanceof HostException h) {
                throw h;
            }
            throw new HostException(HttpStatus.BAD_GATEWAY, "UNREACHABLE", "The file could not be read from the container");
        }
    }

    private static ContainerFileDownload openDownload(InputStream tar, String path, long max) throws IOException {
        byte[] header = tar.readNBytes(TAR_BLOCK);
        String base = baseName(path);
        char type = header.length == TAR_BLOCK ? (char) header[156] : 'x';
        if (type == '0' || type == 0) {
            long size = parseOctal(header, 124, 12);
            if (size < 0) {
                throw new HostException(HttpStatus.BAD_GATEWAY, "UNREACHABLE", "The file could not be read from the container");
            }
            if (size > max) {
                throw sizeLimit();
            }
            return new ContainerFileDownload(new ExactInputStream(tar, size), base, size, true);
        }
        return new ContainerFileDownload(new SequenceInputStream(new ByteArrayInputStream(header), tar),
                base + ".tar", -1, false);
    }

    /** Uploads one multipart file into the existing directory {@code directory}. */
    @Auditable(action = "CONTAINER_FILE_UPLOAD", targetType = "CONTAINER", targetId = "#containerId",
            details = "{'path': #directory, 'fileName': #file?.originalFilename}")
    public void upload(UUID hostId, String containerId, String directory, MultipartFile file) {
        requireOperate(hostId);
        validateContainerId(containerId);
        String dir = validatePath(directory);
        String name = validateFileName(file == null ? null : file.getOriginalFilename());
        long max = properties.maxBytes().toBytes();
        if (file.getSize() > max) {
            throw sizeLimit();
        }
        try (InputStream content = file.getInputStream();
             InputStream tar = tarOf(name, file.getSize(), content)) {
            call(hostId, (id, endpoint) -> {
                files.upload(id, endpoint, containerId, dir, tar, max + TAR_OVERHEAD);
                return null;
            });
        } catch (IOException e) {
            throw new HostException(HttpStatus.BAD_REQUEST, "UPLOAD_UNREADABLE", "The uploaded file could not be read");
        }
    }

    /** Tar of exactly one regular file (mode 0644): header, content, padding and end-of-archive blocks. */
    static InputStream tarOf(String name, long size, InputStream content) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        TarArchiveOutputStream out = new TarArchiveOutputStream(head);
        out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(size);
        entry.setMode(0644);
        entry.setModTime(new Date());
        out.putArchiveEntry(entry);
        out.flush();
        int pad = (int) ((TAR_BLOCK - size % TAR_BLOCK) % TAR_BLOCK);
        byte[] tail = new byte[pad + 2 * TAR_BLOCK];
        return new SequenceInputStream(new SequenceInputStream(new ByteArrayInputStream(head.toByteArray()), content),
                new ByteArrayInputStream(tail));
    }

    // ---- validation ----

    /** Absolute container path: no NUL, no {@code ..} segments, bounded length. */
    static String validatePath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/' || path.indexOf('\0') >= 0
                || path.length() > MAX_PATH_LENGTH) {
            throw invalidPath();
        }
        for (String part : path.split("/")) {
            if (part.equals("..")) {
                throw invalidPath();
            }
        }
        return path;
    }

    private static String validateFileName(String name) {
        if (name == null || name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_FILE_NAME", "The file name is not valid");
        }
        return name;
    }

    private static void validateContainerId(String value) {
        if (value == null || !RESOURCE_ID.matcher(value).matches()) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_RESOURCE_ID", "Invalid resource id");
        }
    }

    private static HostException invalidPath() {
        return new HostException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "The path must be an absolute container path");
    }

    private static HostException sizeLimit() {
        return new HostException(HttpStatus.PAYLOAD_TOO_LARGE, "SIZE_LIMIT_EXCEEDED",
                "The transfer exceeds the size limit");
    }

    private static String baseName(String path) {
        String trimmed = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        String base = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        return base.isEmpty() ? "root" : base.replaceAll("[\\p{Cntrl}\"\\\\]", "_");
    }

    private static long parseOctal(byte[] header, int offset, int length) {
        long value = 0;
        boolean digits = false;
        for (int i = offset; i < offset + length; i++) {
            byte b = header[i];
            if (b >= '0' && b <= '7') {
                value = value * 8 + (b - '0');
                digits = true;
            } else if (digits || (b != ' ' && b != 0)) {
                break;
            }
        }
        return digits ? value : -1;
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing left to do
        }
    }

    // ---- access and docker call ----

    private void requireOperate(UUID hostId) {
        if (!access.canView(hostId)) {
            throw new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found");
        }
        if (!access.canOperate(hostId)) {
            throw new HostException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
        }
    }

    private <T> T call(UUID hostId, BiFunction<UUID, DockerEndpoint, T> query) {
        DockerHost host = hosts.findById(hostId).orElseThrow(
                () -> new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found"));
        try {
            return query.apply(host.getId(), endpoints.endpointOf(host));
        } catch (DockerConnectionException e) {
            throw translate(e);
        } catch (EncryptionKeyMissingException e) {
            throw new HostException(HttpStatus.BAD_GATEWAY, "ENCRYPTION_KEY_MISSING", "No encryption key is configured");
        } catch (SecretDecryptionException e) {
            throw new HostException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED",
                    "Stored connection secrets could not be decrypted");
        }
    }

    private HostException translate(DockerConnectionException e) {
        return switch (e.getCode()) {
            case NOT_FOUND -> new HostException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Container or path not found");
            case INVALID_PATH -> invalidPath();
            case SIZE_LIMIT_EXCEEDED -> sizeLimit();
            case DIRECTORY_TOO_LARGE -> new HostException(HttpStatus.PAYLOAD_TOO_LARGE, "DIRECTORY_TOO_LARGE",
                    "The directory is too large to list");
            case DIRECTORY_EXEC_UNAVAILABLE -> new HostException(HttpStatus.CONFLICT, "DIRECTORY_NOT_LISTABLE",
                    "Listing a directory this large needs a running container with find in its image");
            case INVALID_STATE -> new HostException(HttpStatus.CONFLICT, "INVALID_CONTAINER_STATE",
                    "The container is not in a state that allows this action");
            default -> new HostException(HttpStatus.BAD_GATEWAY, ViewerErrorCodes.forViewer(e.getCode(), access.isAdmin()), "Environment could not be reached");
        };
    }

    /** Passes through at most {@code size} bytes, then reports end of stream (drops tar padding and trailer). */
    private static final class ExactInputStream extends FilterInputStream {

        private long remaining;

        ExactInputStream(InputStream in, long size) {
            super(in);
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(buf, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }
}
