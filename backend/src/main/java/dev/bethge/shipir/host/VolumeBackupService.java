package dev.bethge.shipir.host;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.VolumeArchiveService;
import dev.bethge.shipir.host.dto.VolumeBackupDownload;
import dev.bethge.shipir.permission.CurrentAccess;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Backup (tar download) and restore (tar upload) of a named volume, plus the unattended backup used by schedules.
 * ADMIN/OPERATOR via SecurityConfig, and host-level OPERATE here: 404 when the host is not visible, 403 without it.
 * Archive contents never reach the audit log or the logs.
 */
@Service
@EnableConfigurationProperties(VolumeBackupProperties.class)
public class VolumeBackupService {

    private static final Pattern VOLUME_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final VolumeArchiveService archives;
    private final VolumeBackupStore store;
    private final CurrentAccess access;
    private final VolumeBackupProperties properties;
    private final Clock clock;

    VolumeBackupService(DockerHostRepository hosts, HostEndpointResolver endpoints, VolumeArchiveService archives,
                        VolumeBackupStore store, CurrentAccess access, VolumeBackupProperties properties, Clock clock) {
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
        } catch (IOException e) {
            throw new HostException(HttpStatus.INTERNAL_SERVER_ERROR, "BACKUP_STORE_FAILED", "The backup could not be stored");
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

    private static HostException translate(DockerConnectionException e) {
        return switch (e.getCode()) {
            case NOT_FOUND -> new HostException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Volume not found");
            case IN_USE -> new HostException(HttpStatus.CONFLICT, "VOLUME_IN_USE",
                    "The volume is used by a container; stop it or confirm the restore");
            case INVALID_ARCHIVE -> new HostException(HttpStatus.BAD_REQUEST, "INVALID_ARCHIVE",
                    "The upload is not a valid, safe volume archive");
            case INVALID_VOLUME_NAME -> new HostException(HttpStatus.BAD_REQUEST, "INVALID_RESOURCE_ID", "Invalid resource id");
            case SIZE_LIMIT_EXCEEDED -> sizeLimit();
            default -> new HostException(HttpStatus.BAD_GATEWAY, e.getCode().name(), "Environment could not be reached");
        };
    }

    private static HostException sizeLimit() {
        return new HostException(HttpStatus.PAYLOAD_TOO_LARGE, "SIZE_LIMIT_EXCEEDED", "The transfer exceeds the size limit");
    }
}
