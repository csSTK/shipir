package dev.bethge.shipir.host;

import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.common.util.PathValidationUtil;
import dev.bethge.shipir.host.dto.StoredVolumeBackup;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Archives of scheduled backups: {@code <dir>/<hostId>/<volume>/<volume>-<yyyyMMdd'T'HHmmss'Z'>.tar}. Written to a
 * temporary file first and moved into place, so a failed run leaves no partial archive. Retention deletes only
 * files of exactly that name pattern.
 */
@Component
class VolumeBackupStore {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final VolumeBackupProperties properties;

    VolumeBackupStore(VolumeBackupProperties properties) {
        this.properties = properties;
    }

    /** Stores the stream, then deletes all but the newest {@code keep} archives of the volume. Returns the new file. */
    Path write(UUID hostId, String volume, InputStream content, Instant now, int keep) throws IOException {
        Path base = properties.dir().toAbsolutePath().normalize();
        Files.createDirectories(base);
        Path dir = PathValidationUtil.resolve(base, hostId + "/" + volume);
        Files.createDirectories(dir);
        Path target = dir.resolve(volume + "-" + STAMP.format(now) + ".tar");
        Path partial = Files.createTempFile(dir, ".backup-", ".part");
        try {
            Files.copy(content, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial);
        }
        prune(dir, volume, keep);
        return target;
    }

    /** The stored archives of one volume on one host, newest first. Empty when nothing was stored yet. */
    List<StoredVolumeBackup> list(UUID hostId, String volume) {
        Path dir = volumeDir(hostId, volume);
        if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Pattern name = namePattern(volume);
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> name.matcher(p.getFileName().toString()).matches())
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(p -> toBackup(p, volume))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(StoredVolumeBackup::name).reversed())
                    .toList();
        } catch (IOException e) {
            throw new HostException(HttpStatus.INTERNAL_SERVER_ERROR, "BACKUP_STORE_FAILED", "The stored backups could not be read");
        }
    }

    /**
     * The file of one stored archive, or empty when it does not exist. The name must be exactly
     * {@code <volume>-<yyyyMMdd'T'HHmmss'Z'>.tar} (400 INVALID_RESOURCE_ID otherwise); the file must be a regular file
     * (no symlink) that really lives in the directory of that host and volume.
     */
    Optional<Path> resolve(UUID hostId, String volume, String fileName) {
        if (fileName == null || !namePattern(volume).matcher(fileName).matches()) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_RESOURCE_ID", "Invalid resource id");
        }
        Path dir = volumeDir(hostId, volume);
        if (dir == null) {
            return Optional.empty();
        }
        try {
            Path file = PathValidationUtil.resolve(dir, fileName);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Path expected = properties.dir().toRealPath().resolve(hostId.toString()).resolve(volume).resolve(fileName);
            return file.toRealPath().equals(expected) ? Optional.of(file) : Optional.empty();
        } catch (PathValidationException | IOException e) {
            return Optional.empty(); // a symlink leaving the store counts as not found
        }
    }

    /** {@code <dir>/<hostId>/<volume>}, or null when the store directory or that subtree does not exist. */
    private Path volumeDir(UUID hostId, String volume) {
        Path base = properties.dir().toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            return null;
        }
        Path dir = PathValidationUtil.resolve(base, hostId + "/" + volume);
        return Files.isDirectory(dir) ? dir : null;
    }

    private static Pattern namePattern(String volume) {
        return Pattern.compile(Pattern.quote(volume) + "-(\\d{8}T\\d{6}Z)\\.tar");
    }

    private static StoredVolumeBackup toBackup(Path file, String volume) {
        String name = file.getFileName().toString();
        String stamp = name.substring(volume.length() + 1, name.length() - ".tar".length());
        try {
            return new StoredVolumeBackup(name, Files.size(file), Instant.from(STAMP.parse(stamp)));
        } catch (DateTimeException e) {
            return null; // right shape, impossible date: not one of ours
        } catch (IOException e) {
            throw new HostException(HttpStatus.INTERNAL_SERVER_ERROR, "BACKUP_STORE_FAILED", "The stored backups could not be read");
        }
    }

    /**
     * Deletes every stored archive of the volume (exact name pattern only) and then the volume directory if it is
     * empty. Symlinks are never followed: a symlinked host/volume directory or a symlinked archive is left alone.
     * Returns the number of deleted archives; 0 when nothing was stored.
     */
    int deleteAll(UUID hostId, String volume) {
        try {
            Path base = properties.dir().toAbsolutePath().normalize();
            if (!Files.isDirectory(base)) {
                return 0;
            }
            Path dir = PathValidationUtil.resolve(base, hostId + "/" + volume);
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                    || !dir.toRealPath().equals(base.toRealPath().resolve(hostId.toString()).resolve(volume))) {
                return 0; // missing, or a symlink somewhere on the way: not ours to touch
            }
            Pattern name = namePattern(volume);
            int deleted = 0;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(p -> name.matcher(p.getFileName().toString()).matches())
                        .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    if (Files.deleteIfExists(file)) {
                        deleted++;
                    }
                }
            }
            try {
                Files.delete(dir); // only succeeds when empty
            } catch (DirectoryNotEmptyException e) {
                // other files stay, so does the directory
            }
            return deleted;
        } catch (PathValidationException e) {
            return 0;
        } catch (IOException e) {
            throw new HostException(HttpStatus.INTERNAL_SERVER_ERROR, "BACKUP_STORE_FAILED", "The stored backups could not be deleted");
        }
    }

    private static void prune(Path dir, String volume, int keep) throws IOException {
        Pattern name = Pattern.compile(Pattern.quote(volume) + "-\\d{8}T\\d{6}Z\\.tar");
        List<Path> archives;
        try (Stream<Path> files = Files.list(dir)) {
            archives = files.filter(p -> name.matcher(p.getFileName().toString()).matches())
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .toList();
        }
        for (Path old : archives.stream().skip(keep).toList()) {
            Files.deleteIfExists(old);
        }
    }
}
