package dev.bethge.shipir.host;

import dev.bethge.shipir.common.util.PathValidationUtil;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
