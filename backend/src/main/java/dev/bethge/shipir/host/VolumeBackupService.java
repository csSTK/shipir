package dev.bethge.shipir.host;

import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.ViewerErrorCodes;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.VolumeArchiveService;
import dev.bethge.shipir.host.dto.StoredVolumeBackup;
import dev.bethge.shipir.host.dto.VolumeBackupDownload;
import dev.bethge.shipir.permission.CurrentAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Backup (tar download) and restore (tar upload) of a named volume, plus the unattended backup used by schedules.
 * ADMIN/OPERATOR via SecurityConfig, and host-level OPERATE here: 404 when the host is not visible, 403 without it.
 * Volumes of the system-managed proxy are ADMIN only ({@link SystemManagedGuard}).
 * Archive contents never reach the audit log or the logs.
 */
@Service
@EnableConfigurationProperties(VolumeBackupProperties.class)
public class VolumeBackupService {

    private static final Logger log = LoggerFactory.getLogger(VolumeBackupService.class);

    private static final Pattern VOLUME_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final VolumeArchiveService archives;
    private final VolumeBackupStore store;
    private final CurrentAccess access;
    private final VolumeBackupProperties properties;
    private final Clock clock;
    private final SystemManagedGuard systemManaged;

    VolumeBackupService(DockerHostRepository hosts, HostEndpointResolver endpoints, VolumeArchiveService archives,
                        VolumeBackupStore store, CurrentAccess access, VolumeBackupProperties properties, Clock clock,
                        SystemManagedGuard systemManaged) {
        this.systemManaged = systemManaged;
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.archives = archives;
        this.store = store;
        this.access = access;
        this.properties = properties;
        this.clock = clock;
    }

    /** Opens the backup stream; the caller must close it (that removes the helper container). */
    @Auditable(action = "VOLUME_BACKUP", targetType = "VOLUME", targetId = "#volume")
    public VolumeBackupDownload backup(UUID hostId, String volume) {
        requireOperate(hostId);
        validateVolumeName(volume);
        systemManaged.requireNotSystemManagedVolume(hostId, volume);
        long max = properties.maxBytes().toBytes();
        InputStream content = call(hostId, (id, endpoint) ->
                archives.backup(id, endpoint, volume, properties.helperImage(), max));
        return new VolumeBackupDownload(content, volume + "-" + STAMP.format(clock.instant()) + ".tar");
    }

    /**
     * Extracts the uploaded tar into the existing volume (merging over what is there). A volume that a container
     * mounts is refused unless {@code confirm} is true.
     */
    @Auditable(action = "VOLUME_RESTORE", targetType = "VOLUME", targetId = "#volume",
            details = "{'confirm': #confirm, 'bytes': #file?.size}")
    public void restore(UUID hostId, String volume, MultipartFile file, boolean confirm) {
        requireOperate(hostId);
        validateVolumeName(volume);
        systemManaged.requireNotSystemManagedVolume(hostId, volume);
        if (file == null || file.isEmpty()) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_ARCHIVE", "The upload is empty");
        }
        long max = properties.maxBytes().toBytes();
        if (file.getSize() > max) {
            throw sizeLimit();
        }
        call(hostId, (id, endpoint) -> {
            archives.restore(id, endpoint, volume, properties.helperImage(), file::getInputStream, max, confirm);
            return null;
        });
    }

    /**
     * Unattended run of a schedule: writes the archive to the store and applies retention. Audited as the system
     * user; no permission check (the schedule itself was created by a permitted user).
     */
    @Auditable(action = "VOLUME_BACKUP", targetType = "VOLUME", targetId = "#volume", username = "'system'",
            details = "{'scheduled': true}")
    public void storeScheduledBackup(UUID hostId, String volume, int keep) {
        validateVolumeName(volume);
        long max = properties.maxBytes().toBytes();
        try (InputStream content = call(hostId, (id, endpoint) ->
                archives.backup(id, endpoint, volume, properties.helperImage(), max))) {
            store.write(hostId, volume, content, clock.instant(), keep);
        } catch (IOException | PathValidationException e) {
            throw new HostException(HttpStatus.INTERNAL_SERVER_ERROR, "BACKUP_STORE_FAILED", "The backup could not be stored");
        }
    }

    /** Archives written by scheduled backups for this volume, newest first. */
    public List<StoredVolumeBackup> listStored(UUID hostId, String volume) {
        requireOperate(hostId);
        validateVolumeName(volume);
        systemManaged.requireNotSystemManagedVolume(hostId, volume);
        return store.list(hostId, volume);
    }

    /** Opens one stored archive for download; the caller must close the stream. */
    @Auditable(action = "VOLUME_BACKUP_DOWNLOAD", targetType = "VOLUME", targetId = "#volume", details = "{'file': #fileName}")
    public VolumeBackupDownload downloadStored(UUID hostId, String volume, String fileName) {
        requireOperate(hostId);
        validateVolumeName(volume);
        systemManaged.requireNotSystemManagedVolume(hostId, volume);
        return new VolumeBackupDownload(openStored(hostId, volume, fileName), fileName);
    }

    /** Opens a stored archive through the store's no-follow handle walk: 404 if missing or a link, 500 on other I/O errors. */
    private InputStream openStored(UUID hostId, String volume, String fileName) {
        try {
            return store.open(hostId, volume, fileName).orElseThrow(
                    () -> new HostException(HttpStatus.NOT_FOUND, "STORED_BACKUP_NOT_FOUND", "Stored backup not found"));
        } catch (IOException e) {
            throw unreadable();
        }
    }

    /**
     * Restores a stored scheduled archive through the same pipeline as an upload (tar validation, size cap, the
     * unused-or-confirm rule, helper container always removed). Permission is checked before the name is resolved.
     */
    @Auditable(action = "VOLUME_RESTORE", targetType = "VOLUME", targetId = "#volume",
            details = "{'confirm': #confirm, 'file': #fileName, 'source': 'stored'}")
    public void restoreStored(UUID hostId, String volume, String fileName, boolean confirm) {
        requireOperate(hostId);
        validateVolumeName(volume);
        systemManaged.requireNotSystemManagedVolume(hostId, volume);
        long max = properties.maxBytes().toBytes();
        // The archive is read from disk exactly once, into a private copy; the restore pipeline (which opens its source
        // twice, to validate and to stream) works on the copy. A file swapped or rewritten in the backup directory
        // meanwhile can therefore never make the validated bytes differ from the extracted bytes. This is an integrity
        // guard, not an access control: someone who can write the backup directory can still plant a (valid) archive
        // before the copy is made.
        Path copy;
        try (InputStream stored = openStored(hostId, volume, fileName)) {
            copy = copyPrivately(stored, max, null);
        } catch (IOException e) {
            throw unreadable();
        }
        try {
            call(hostId, (id, endpoint) -> {
                archives.restore(id, endpoint, volume, properties.helperImage(), () -> Files.newInputStream(copy), max, confirm);
                return null;
            });
        } catch (HostException e) {
            if ("INVALID_ARCHIVE".equals(e.code())) {
                throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_ARCHIVE", "The stored archive is not a valid, safe volume archive");
            }
            throw e;
        } finally {
            try {
                Files.deleteIfExists(copy);
            } catch (IOException e) {
                log.warn("Could not delete the temporary restore copy {}: {}", copy.getFileName(), e.toString());
            }
        }
    }

    private static HostException unreadable() {
        return new HostException(HttpStatus.INTERNAL_SERVER_ERROR, "STORED_BACKUP_UNREADABLE",
                "The stored backup could not be read");
    }

    /**
     * Copies the stored archive (already opened through the store's no-follow walk) into an owner-only temp file,
     * reading at most {@code max} bytes (413 when it is larger).
     * <p>
     * The temp file is created in {@code tempDir}, {@code null} for {@code java.io.tmpdir}, which may be a small tmpfs
     * (e.g. in a container). A full tmpdir or an archive that cannot be read is a server problem and gives 500
     * STORED_BACKUP_UNREADABLE.
     */
    static Path copyPrivately(InputStream in, long max, Path tempDir) {
        Path copy = null;
        try {
            copy = tempDir == null
                    ? Files.createTempFile("shipir-restore-", ".tar")
                    : Files.createTempFile(tempDir, "shipir-restore-", ".tar"); // owner-only (0600) on POSIX
            try (var out = Files.newOutputStream(copy)) {
                // at most max + 1 bytes: a file that grew past the limit is refused without filling the disk
                long copied = copyBounded(in, out, max + 1);
                if (copied > max) {
                    throw sizeLimit();
                }
            }
            return copy;
        } catch (IOException e) {
            deleteQuietly(copy);
            throw unreadable();
        } catch (RuntimeException e) {
            deleteQuietly(copy);
            throw e;
        }
    }

    private static long copyBounded(InputStream in, java.io.OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        while (total < limit) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, limit - total));
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    static void validateVolumeName(String name) {
        if (name == null || !VOLUME_NAME.matcher(name).matches()) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_RESOURCE_ID", "Invalid resource id");
        }
    }

    private void requireOperate(UUID hostId) {
        if (!access.canView(hostId)) {
            throw new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found");
        }
        if (!access.canOperate(hostId)) {
            throw new HostException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
        }
    }

    private <T> T call(UUID hostId, BiFunction<UUID, DockerEndpoint, T> action) {
        DockerHost host = hosts.findById(hostId).orElseThrow(
                () -> new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found"));
        try {
            return action.apply(host.getId(), endpoints.endpointOf(host));
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
            case NOT_FOUND -> new HostException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Volume not found");
            case IN_USE -> new HostException(HttpStatus.CONFLICT, "VOLUME_IN_USE",
                    "The volume is used by a container; stop it or confirm the restore");
            case INVALID_ARCHIVE -> new HostException(HttpStatus.BAD_REQUEST, "INVALID_ARCHIVE",
                    "The upload is not a valid, safe volume archive");
            case INVALID_VOLUME_NAME -> new HostException(HttpStatus.BAD_REQUEST, "INVALID_RESOURCE_ID", "Invalid resource id");
            case SIZE_LIMIT_EXCEEDED -> sizeLimit();
            default -> new HostException(HttpStatus.BAD_GATEWAY, ViewerErrorCodes.forViewer(e.getCode(), access.isAdmin()), "Environment could not be reached");
        };
    }

    private static HostException sizeLimit() {
        return new HostException(HttpStatus.PAYLOAD_TOO_LARGE, "SIZE_LIMIT_EXCEEDED", "The transfer exceeds the size limit");
    }
}
