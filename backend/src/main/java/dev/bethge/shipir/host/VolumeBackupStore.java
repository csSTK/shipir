package dev.bethge.shipir.host;

import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.common.util.PathValidationUtil;
import dev.bethge.shipir.common.util.SecureFiles;
import dev.bethge.shipir.host.dto.StoredVolumeBackup;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Archives of scheduled backups: {@code <dir>/<hostId>/<volume>/<volume>-<yyyyMMdd'T'HHmmss'Z'>.tar}. Written to a
 * temporary file first and moved into place, so a failed run leaves no partial archive. Retention deletes only
 * files of exactly that name pattern. A crash between the temp file and the move leaves a {@code .backup-*.part}
 * file; {@link #pruneStalePartials} removes those (the scheduler calls it once at start).
 */
@Component
class VolumeBackupStore {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final Pattern PARTIAL = Pattern.compile("\\.backup-.*\\.part");

    private static final FileAttribute<Set<PosixFilePermission>> DIR_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> FILE_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private final VolumeBackupProperties properties;
    private final Clock clock;

    VolumeBackupStore(VolumeBackupProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Stores the stream, then deletes all but the newest {@code keep} archives of the volume. Returns the new file.
     * Host and volume directories are walked (and created when absent) through held no-follow handles and every file
     * operation happens relative to the volume handle, so a link swapped in for a directory is refused or ignored,
     * never followed.
     */
    Path write(UUID hostId, String volume, InputStream content, Instant now, int keep) throws IOException {
        Path base = properties.dir().toAbsolutePath().normalize();
        Files.createDirectories(base);
        Path dir = PathValidationUtil.resolve(base, hostId + "/" + volume);
        String targetName = volume + "-" + STAMP.format(now) + ".tar";
        String partialName = ".backup-" + UUID.randomUUID() + ".part";
        try (SecureFiles.Parent parent = SecureFiles.openParent(base, hostId + "/" + volume + "/" + targetName, true, seam,
                DIR_PERMISSIONS)) {
            SecureFiles.Dir volumeDir = parent.dir();
            try {
                try (SeekableByteChannel out = volumeDir.createNew(partialName, FILE_PERMISSIONS);
                     OutputStream stream = Channels.newOutputStream(out)) {
                    content.transferTo(stream);
                }
                volumeDir.move(partialName, targetName);
            } finally {
                volumeDir.deleteFileIfExists(partialName);
            }
        }
        // A directory stream only sees the entries present when it was opened (at least on macOS), so retention walks
        // the volume directory again with a fresh no-follow handle to see the archive just moved into place.
        try (SecureFiles.Parent fresh = SecureFiles.openParent(base, hostId + "/" + volume + "/" + targetName, false, seam)) {
            prune(fresh.dir(), volume, keep);
        } catch (PathValidationException swapped) {
            // the directory became a link after the archive was stored: retention is skipped (next run), never followed
        }
        return dir.resolve(targetName);
    }

    /** The stored archives of one volume on one host, newest first. Empty when nothing was stored yet. */
    List<StoredVolumeBackup> list(UUID hostId, String volume) {
        Path base = properties.dir().toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        PathValidationUtil.resolve(base, hostId + "/" + volume); // up-front validation (traversal)
        Pattern name = namePattern(volume);
        try (SecureFiles.Parent parent = SecureFiles.openParent(base, hostId + "/" + volume + "/x", false, seam)) {
            List<StoredVolumeBackup> result = new ArrayList<>();
            for (String file : parent.dir().list()) {
                if (!name.matcher(file).matches()) {
                    continue;
                }
                BasicFileAttributes attrs;
                try {
                    attrs = parent.dir().attributes(file);
                } catch (NoSuchFileException gone) {
                    continue;
                }
                StoredVolumeBackup backup = attrs.isRegularFile() ? toBackup(file, attrs.size(), volume) : null;
                if (backup != null) {
                    result.add(backup);
                }
            }
            result.sort(Comparator.comparing(StoredVolumeBackup::name).reversed());
            return List.copyOf(result);
        } catch (NoSuchFileException | PathValidationException e) {
            return List.of(); // missing, or a symlinked host/volume directory: not this host's data
        } catch (IOException e) {
            throw new HostException(HttpStatus.INTERNAL_SERVER_ERROR, "BACKUP_STORE_FAILED", "The stored backups could not be read");
        }
    }

    /**
     * Opens one stored archive for reading, or empty when it does not exist. The name must be exactly
     * {@code <volume>-<yyyyMMdd'T'HHmmss'Z'>.tar} (400 INVALID_RESOURCE_ID otherwise). Host directory, volume directory and
     * file are opened one step at a time relative to held handles without following links ({@link SecureFiles}), so a
     * link swapped in between the check and the open is never followed: a symlinked directory or archive, a directory
     * or other non-regular entry counts as not found. Any other I/O failure is thrown. The caller closes the stream.
     */
    Optional<InputStream> open(UUID hostId, String volume, String fileName) throws IOException {
        if (fileName == null || !namePattern(volume).matcher(fileName).matches()) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_RESOURCE_ID", "Invalid resource id");
        }
        Path base = properties.dir().toAbsolutePath().normalize();
        try (SecureFiles.Parent parent = SecureFiles.openParent(base, hostId + "/" + volume + "/" + fileName, false, seam)) {
            if (!parent.dir().isRegularFile(parent.name())) {
                return Optional.empty();
            }
            // the directory handle is closed on leaving the block; the channel keeps its own descriptor
            return Optional.of(Channels.newInputStream(parent.dir().openRead(parent.name())));
        } catch (NoSuchFileException | PathValidationException | SecureFiles.NotRegularFileException e) {
            return Optional.empty(); // missing, or a symlink on the way: not this host's data
        }
    }

    /** Test seam: receives the stages of {@link SecureFiles} (where a symlink swap could strike); null = none. */
    Consumer<String> seam;

    private static Pattern namePattern(String volume) {
        return Pattern.compile(Pattern.quote(volume) + "-(\\d{8}T\\d{6}Z)\\.tar");
    }

    private static StoredVolumeBackup toBackup(String name, long size, String volume) {
        String stamp = name.substring(volume.length() + 1, name.length() - ".tar".length());
        try {
            return new StoredVolumeBackup(name, size, Instant.from(STAMP.parse(stamp)));
        } catch (DateTimeException e) {
            return null; // right shape, impossible date: not one of ours
        }
    }

    /**
     * Deletes every stored archive of the volume (exact name pattern only) and then the volume directory if it is
     * empty. The host directory is held open and the volume directory is opened from it without following links, so a
     * symlinked host/volume directory or a symlinked archive is left alone, and a directory swapped for a link while
     * deleting is never followed. Returns the number of deleted archives; 0 when nothing was stored.
     */
    int deleteAll(UUID hostId, String volume) {
        try {
            Path base = properties.dir().toAbsolutePath().normalize();
            if (!Files.isDirectory(base)) {
                return 0;
            }
            PathValidationUtil.resolve(base, hostId + "/" + volume); // up-front validation (traversal)
            Pattern name = namePattern(volume);
            int deleted = 0;
            try (SecureFiles.Parent parent = SecureFiles.openParent(base, hostId + "/" + volume, false, seam)) {
                try (SecureFiles.Dir volumeDir = parent.dir().openSubdirectory(parent.name())) {
                    for (String file : volumeDir.list()) {
                        if (name.matcher(file).matches() && volumeDir.isRegularFile(file)) {
                            try {
                                volumeDir.deleteFile(file);
                                deleted++;
                            } catch (NoSuchFileException gone) {
                                // removed concurrently
                            }
                        }
                    }
                }
                try {
                    parent.dir().deleteDirectory(parent.name()); // only succeeds when empty
                } catch (DirectoryNotEmptyException e) {
                    // other files stay, so does the directory
                }
            }
            return deleted;
        } catch (NoSuchFileException | PathValidationException e) {
            return 0; // missing, or a symlink on the way: not ours to touch
        } catch (IOException e) {
            throw new HostException(HttpStatus.INTERNAL_SERVER_ERROR, "BACKUP_STORE_FAILED", "The stored backups could not be deleted");
        }
    }

    /**
     * Deletes leftover {@code .backup-*.part} files (a crash between the temp file and the atomic move in
     * {@link #write}) at depth {@code <dir>/<hostId>/<volume>/} that were last modified at least {@code minAge} ago.
     * Host and volume directories are opened from held handles without following links: symlinked directories and
     * symlinked files are never followed or touched. Returns the number of deleted files; 0 when the store directory
     * does not exist (or is itself a symlink).
     */
    int pruneStalePartials(Duration minAge) {
        Path base = properties.dir().toAbsolutePath().normalize();
        FileTime cutoff = FileTime.from(clock.instant().minus(minAge));
        int deleted = 0;
        try (SecureFiles.Parent root = SecureFiles.openParent(base, "x", false, seam)) {
            for (String host : root.dir().list()) {
                try (SecureFiles.Dir hostDir = openRealDirectory(root.dir(), host)) {
                    if (hostDir == null) {
                        continue;
                    }
                    for (String volume : hostDir.list()) {
                        try (SecureFiles.Dir volumeDir = openRealDirectory(hostDir, volume)) {
                            if (volumeDir != null) {
                                deleted += prunePartials(volumeDir, cutoff);
                            }
                        }
                    }
                }
            }
        } catch (NoSuchFileException | PathValidationException e) {
            return deleted; // no store directory (or it is a symlink): nothing to clean
        } catch (IOException e) {
            throw new HostException(HttpStatus.INTERNAL_SERVER_ERROR, "BACKUP_STORE_FAILED", "The stored backups could not be cleaned up");
        }
        return deleted;
    }

    private static int prunePartials(SecureFiles.Dir volumeDir, FileTime cutoff) throws IOException {
        int deleted = 0;
        for (String file : volumeDir.list()) {
            if (!PARTIAL.matcher(file).matches() || !volumeDir.isRegularFile(file)) {
                continue;
            }
            try {
                if (volumeDir.lastModified(file).compareTo(cutoff) <= 0) {
                    volumeDir.deleteFile(file);
                    deleted++;
                }
            } catch (NoSuchFileException gone) {
                // removed concurrently
            }
        }
        return deleted;
    }

    /** The real (non-symlink) subdirectory {@code name} of {@code parent}, or null for a file, a link or a vanished entry. */
    private static SecureFiles.Dir openRealDirectory(SecureFiles.Dir parent, String name) throws IOException {
        if (!parent.isDirectory(name)) {
            return null;
        }
        try {
            return parent.openSubdirectory(name);
        } catch (NoSuchFileException | PathValidationException swapped) {
            return null;
        }
    }

    private static void prune(SecureFiles.Dir volumeDir, String volume, int keep) throws IOException {
        Pattern name = namePattern(volume);
        List<String> archives = volumeDir.list().stream()
                .filter(n -> name.matcher(n).matches())
                .filter(volumeDir::isRegularFile)
                .sorted(Comparator.reverseOrder())
                .toList();
        for (String old : archives.stream().skip(keep).toList()) {
            volumeDir.deleteFileIfExists(old);
        }
    }
}
