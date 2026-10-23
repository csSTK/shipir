package dev.bethge.shipir.compose.file;

import dev.bethge.shipir.common.util.PathValidationUtil;
import dev.bethge.shipir.compose.StackProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Stores {@code <base>/<hostId>/<stackName>/{compose.yaml,.env}} on disk. Every path is resolved through
 * {@link PathValidationUtil}. Directories are 0700, files 0600. File contents (especially {@code .env}) are never logged.
 */
@Repository
@EnableConfigurationProperties(StackProperties.class)
public class ComposeFileRepository {

    public static final String COMPOSE_FILE = "compose.yaml";
    public static final String ENV_FILE = ".env";
    public static final String REPO_DIR = "repo";

    private static final FileAttribute<Set<PosixFilePermission>> DIR_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> FILE_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private final Path baseDir;

    public ComposeFileRepository(StackProperties properties) {
        this.baseDir = properties.baseDir().toAbsolutePath().normalize();
    }

    /** Writes compose.yaml and, if {@code envContent} is non-null, .env; a null {@code envContent} removes an existing .env. */
    public void write(UUID hostId, String stackName, String composeContent, String envContent) {
        Path dir = ensureStackDir(hostId, stackName);
        writeAtomically(dir.resolve(COMPOSE_FILE), composeContent);
        if (envContent != null) {
            writeAtomically(dir.resolve(ENV_FILE), envContent);
        } else {
            deleteQuietly(dir.resolve(ENV_FILE));
        }
    }

    public Optional<String> readCompose(UUID hostId, String stackName) {
        return read(stackDir(hostId, stackName).resolve(COMPOSE_FILE));
    }

    public Optional<String> readEnv(UUID hostId, String stackName) {
        return read(stackDir(hostId, stackName).resolve(ENV_FILE));
    }

    /** Removes the whole stack directory including a Git checkout in {@code repo/}. Symlinks are removed, never followed. */
    public void delete(UUID hostId, String stackName) {
        Path dir = stackDir(hostId, stackName);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(ComposeFileRepository::deleteOrThrow);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete stack directory", e);
        }
    }

    /** Validated stack directory (may not exist yet); working directory for compose execution. */
    public Path stackDir(UUID hostId, String stackName) {
        return PathValidationUtil.resolve(existingBaseDir(), segment(hostId, stackName));
    }

    /** Validated Git checkout location {@code <stackDir>/repo} (may not exist yet). */
    public Path repoDir(UUID hostId, String stackName) {
        return PathValidationUtil.resolve(existingBaseDir(), segment(hostId, stackName) + "/" + REPO_DIR);
    }

    private static String segment(UUID hostId, String stackName) {
        if (hostId == null) {
            throw new IllegalArgumentException("hostId is required");
        }
        return hostId + "/" + PathValidationUtil.validateStackName(stackName);
    }

    private Path existingBaseDir() {
        try {
            Files.createDirectories(baseDir, DIR_PERMISSIONS);
            return baseDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create stacks base directory", e);
        }
    }

    private Path ensureStackDir(UUID hostId, String stackName) {
        Path dir = stackDir(hostId, stackName);
        try {
            Files.createDirectories(dir.getParent(), DIR_PERMISSIONS);
            if (!Files.isDirectory(dir)) {
                Files.createDirectory(dir, DIR_PERMISSIONS);
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create stack directory", e);
        }
    }

    private static void writeAtomically(Path target, String content) {
        Path temp = null;
        try {
            temp = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp", FILE_PERMISSIONS);
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (temp != null) {
                deleteQuietly(temp);
            }
            throw new UncheckedIOException("Could not write " + target.getFileName(), e);
        }
    }

    private static Optional<String> read(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file.getFileName(), e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void deleteOrThrow(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + path.getFileName(), e);
        }
    }
}
