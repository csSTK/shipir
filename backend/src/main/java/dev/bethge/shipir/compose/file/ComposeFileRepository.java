package dev.bethge.shipir.compose.file;

import dev.bethge.shipir.common.util.PathValidationUtil;
import dev.bethge.shipir.compose.DeploymentProperties;
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
 * Stores {@code <base>/<hostId>/<deploymentName>/{compose.yaml,.env}} on disk. Every path is resolved through
 * {@link PathValidationUtil}. Directories are 0700, files 0600. File contents (especially {@code .env}) are never logged.
 */
@Repository
@EnableConfigurationProperties(DeploymentProperties.class)
public class ComposeFileRepository {

    public static final String COMPOSE_FILE = "compose.yaml";
    public static final String ENV_FILE = ".env";
    /** Temporary compose file merged over {@code compose.yaml} while rolling back an image update. */
    public static final String ROLLBACK_OVERRIDE_FILE = "compose.rollback.yaml";
    public static final String REPO_DIR = "repo";

    private static final FileAttribute<Set<PosixFilePermission>> DIR_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> FILE_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private final Path baseDir;

    public ComposeFileRepository(DeploymentProperties properties) {
        this.baseDir = properties.baseDir().toAbsolutePath().normalize();
    }

    /** Writes compose.yaml and, if {@code envContent} is non-null, .env; a null {@code envContent} removes an existing .env. */
    public void write(UUID hostId, String deploymentName, String composeContent, String envContent) {
        Path dir = ensureDeploymentDir(hostId, deploymentName);
        writeAtomically(dir.resolve(COMPOSE_FILE), composeContent);
        if (envContent != null) {
            writeAtomically(dir.resolve(ENV_FILE), envContent);
        } else {
            deleteQuietly(dir.resolve(ENV_FILE));
        }
    }

    /** Writes the temporary rollback override next to {@code compose.yaml}; returns its path. */
    public Path writeRollbackOverride(UUID hostId, String deploymentName, String content) {
        Path dir = ensureDeploymentDir(hostId, deploymentName);
        Path file = dir.resolve(ROLLBACK_OVERRIDE_FILE);
        writeAtomically(file, content);
        return file;
    }

    public void deleteRollbackOverride(UUID hostId, String deploymentName) {
        deleteQuietly(deploymentDir(hostId, deploymentName).resolve(ROLLBACK_OVERRIDE_FILE));
    }

    public Optional<String> readCompose(UUID hostId, String deploymentName) {
        return read(deploymentDir(hostId, deploymentName).resolve(COMPOSE_FILE));
    }

    public Optional<String> readEnv(UUID hostId, String deploymentName) {
        return read(deploymentDir(hostId, deploymentName).resolve(ENV_FILE));
    }

    /** Removes the whole deployment directory including a Git checkout in {@code repo/}. Symlinks are removed, never followed. */
    public void delete(UUID hostId, String deploymentName) {
        Path dir = deploymentDir(hostId, deploymentName);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(ComposeFileRepository::deleteOrThrow);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete deployment directory", e);
        }
    }

    /** Validated deployment directory (may not exist yet); working directory for compose execution. */
    public Path deploymentDir(UUID hostId, String deploymentName) {
        return PathValidationUtil.resolve(existingBaseDir(), segment(hostId, deploymentName));
    }

    /** Validated Git checkout location {@code <deploymentDir>/repo} (may not exist yet). */
    public Path repoDir(UUID hostId, String deploymentName) {
        return PathValidationUtil.resolve(existingBaseDir(), segment(hostId, deploymentName) + "/" + REPO_DIR);
    }

    /**
     * Validated location of a Git deployment's compose file {@code <deploymentDir>/repo/<composePath>} (symlinks leaving the
     * checkout are rejected); working directory source for relative paths in the compose file.
     *
     * @throws dev.bethge.shipir.common.util.PathValidationException for traversal, absolute or escaping paths
     */
    public Path repoComposeFile(UUID hostId, String deploymentName, String composePath) {
        return PathValidationUtil.resolve(repoDir(hostId, deploymentName), composePath);
    }

    private static String segment(UUID hostId, String deploymentName) {
        if (hostId == null) {
            throw new IllegalArgumentException("hostId is required");
        }
        return hostId + "/" + PathValidationUtil.validateDeploymentName(deploymentName);
    }

    private Path existingBaseDir() {
        try {
            Files.createDirectories(baseDir, DIR_PERMISSIONS);
            return baseDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create deployments base directory", e);
        }
    }

    private Path ensureDeploymentDir(UUID hostId, String deploymentName) {
        Path dir = deploymentDir(hostId, deploymentName);
        try {
            Files.createDirectories(dir.getParent(), DIR_PERMISSIONS);
            if (!Files.isDirectory(dir)) {
                Files.createDirectory(dir, DIR_PERMISSIONS);
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create deployment directory", e);
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
