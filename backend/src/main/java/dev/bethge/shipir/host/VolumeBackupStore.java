package dev.bethge.shipir.host;

import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.common.util.PathValidationUtil;
import dev.bethge.shipir.common.util.SecureFiles;
import dev.bethge.shipir.host.dto.StoredVolumeBackup;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    private final VolumeBackupProperties properties;
    private final Clock clock;

    VolumeBackupStore(VolumeBackupProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Stores the stream, then deletes all but the newest {@code keep} archives of the volume. Returns the new file. */
    Path write(UUID hostId, String volume, InputStream content, Instant now, int keep) throws IOException {
        Path base = properties.dir().toAbsolutePath().normalize();
        Files.createDirectories(base);
        Path dir = PathValidationUtil.resolve(base, hostId + "/" + volume);
        // One level at a time, each only created when absent and never through a symlink, so a planted host or
        // volume symlink cannot make us create directories outside the store.
        Path hostDir = ensureRealDirectory(base.resolve(hostId.toString()));
        ensureRealDirectory(hostDir.resolve(dir.getFileName()));
        if (!isRealVolumeDir(base, hostId, volume, dir)) {
            throw new IOException("Backup directory of the volume is a symlink or leaves the store");
        }
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

    /** Creates {@code dir} when absent; refuses (IOException) anything present that is not a real directory. */
    private static Path ensureRealDirectory(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(dir);
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Backup directory is a symlink or not a directory");
        }
        return dir;
    }

    /** The stored archives of one volume on one host, newest first. Empty when nothing was stored yet. */
    List<StoredVolumeBackup> list(UUID hostId, String volume) {
        Path dir = volumeDir(hostId, volume);
        if (dir == null || !isRealVolumeDir(properties.dir().toAbsolutePath().normalize(), hostId, volume, dir)) {
            return List.of(); // missing, or a symlinked host/volume directory: not this host's data
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

    /** {@code <dir>/<hostId>/<volume>}, or null when the store directory or that subtree does not exist. */
    private Path volumeDir(UUID hostId, String volume) {
        Path base = properties.dir().toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            return null;
        }
        Path dir = PathValidationUtil.resolve(base, hostId + "/" + volume);
        return Files.isDirectory(dir) ? dir : null;
    }

    /** True when {@code dir} is a real directory whose real path is exactly {@code <realBase>/<host>/<volume>}. */
    private static boolean isRealVolumeDir(Path base, UUID hostId, String volume, Path dir) {
        try {
            return Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                    && dir.toRealPath().equals(base.toRealPath().resolve(hostId.toString()).resolve(volume));
        } catch (IOException e) {
            return false;
        }
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

    /**
     * Deletes leftover {@code .backup-*.part} files (a crash between the temp file and the atomic move in
     * {@link #write}) at depth {@code <dir>/<hostId>/<volume>/} that were last modified at least {@code minAge} ago.
     * Symlinked host/volume directories and symlinked files are never followed or touched. Returns the number of
     * deleted files; 0 when the store directory does not exist.
     */
    int pruneStalePartials(Duration minAge) {
        Path base = properties.dir().toAbsolutePath().normalize();
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        FileTime cutoff = FileTime.from(clock.instant().minus(minAge));
        int deleted = 0;
        try {
            for (Path hostDir : realDirectories(base)) {
                for (Path volumeDir : realDirectories(hostDir)) {
                    try (Stream<Path> files = Files.list(volumeDir)) {
                        for (Path file : files.filter(p -> PARTIAL.matcher(p.getFileName().toString()).matches())
                                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                            if (Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).compareTo(cutoff) <= 0
                                    && Files.deleteIfExists(file)) {
                                deleted++;
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new HostException(HttpStatus.INTERNAL_SERVER_ERROR, "BACKUP_STORE_FAILED", "The stored backups could not be cleaned up");
        }
        return deleted;
    }

    /** Direct subdirectories of {@code parent} that are real directories, not symlinks. */
    private static List<Path> realDirectories(Path parent) throws IOException {
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList();
        }
    }

    private static void prune(Path dir, String volume, int keep) throws IOException {
        Pattern name = Pattern.compile(Pattern.quote(volume) + "-\\d{8}T\\d{6}Z\\.tar");
        List<Path> archives;
        try (Stream<Path> files = Files.list(dir)) {
            archives = files.filter(p -> name.matcher(p.getFileName().toString()).matches())
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .toList();
        }
        for (Path old : archives.stream().skip(keep).toList()) {
            Files.deleteIfExists(old);
        }
    }
}
