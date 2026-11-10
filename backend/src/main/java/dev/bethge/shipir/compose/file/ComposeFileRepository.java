package dev.bethge.shipir.compose.file;

import dev.bethge.shipir.common.util.PathValidationUtil;
import dev.bethge.shipir.common.util.SecureFiles;
import dev.bethge.shipir.compose.DeploymentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
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
    /** Every file this repository writes into a deployment directory (other files there are not Shipir-approved). */
    public static final Set<String> SHIPIR_WRITTEN_FILES = Set.of(COMPOSE_FILE, ENV_FILE, ROLLBACK_OVERRIDE_FILE);

    private static final FileAttribute<Set<PosixFilePermission>> DIR_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> FILE_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private final Path baseDir;

    /** Test seam: receives the stages of {@link SecureFiles} (where a symlink swap could strike); null = none. */
    Consumer<String> seam;

    public ComposeFileRepository(DeploymentProperties properties) {
        this.baseDir = properties.baseDir().toAbsolutePath().normalize();
    }

    /** Writes compose.yaml and, if {@code envContent} is non-null, .env; a null {@code envContent} removes an existing .env. */
    public void write(UUID hostId, String deploymentName, String composeContent, String envContent) {
        try (SecureFiles.Parent parent = openDeploymentDir(hostId, deploymentName, true)) {
            writeAtomically(parent.dir(), COMPOSE_FILE, composeContent);
            if (envContent != null) {
                writeAtomically(parent.dir(), ENV_FILE, envContent);
            } else {
                deleteQuietly(parent.dir(), ENV_FILE);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write deployment files", e);
        }
    }

    /** Writes the temporary rollback override next to {@code compose.yaml}; returns its path. */
    public Path writeRollbackOverride(UUID hostId, String deploymentName, String content) {
        Path file = deploymentDir(hostId, deploymentName).resolve(ROLLBACK_OVERRIDE_FILE);
        try (SecureFiles.Parent parent = openDeploymentDir(hostId, deploymentName, true)) {
            writeAtomically(parent.dir(), ROLLBACK_OVERRIDE_FILE, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + ROLLBACK_OVERRIDE_FILE, e);
        }
        return file;
    }

    public void deleteRollbackOverride(UUID hostId, String deploymentName) {
        try (SecureFiles.Parent parent = openDeploymentDir(hostId, deploymentName, false)) {
            deleteQuietly(parent.dir(), ROLLBACK_OVERRIDE_FILE);
        } catch (IOException | RuntimeException ignored) {
            // best effort: nothing to remove, or the directory was swapped and is refused
        }
    }

    public Optional<String> readCompose(UUID hostId, String deploymentName) {
        return read(hostId, deploymentName, COMPOSE_FILE);
    }

    public Optional<String> readEnv(UUID hostId, String deploymentName) {
        return read(hostId, deploymentName, ENV_FILE);
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

    /**
     * Removes only the files Shipir writes ({@code compose.yaml}, {@code .env}, the rollback override) and leaves the rest
     * of the directory, notably a Git checkout in {@code repo/}, alone. A symlink at one of those names is unlinked, never
     * followed. Unlike {@link #write} this fails loudly: callers use it to take back a refused file.
     */
    public void deleteManagedFiles(UUID hostId, String deploymentName) {
        try (SecureFiles.Parent parent = openDeploymentDir(hostId, deploymentName, false)) {
            for (String file : SHIPIR_WRITTEN_FILES) {
                try {
                    parent.dir().deleteFileIfExists(file);
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not delete " + file, e);
                }
            }
        } catch (NoSuchFileException missing) {
            // no deployment directory, nothing to take back
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open deployment directory", e);
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

    /**
     * Opens {@code <base>/<host>/<name>} as a held, no-follow directory handle (the returned parent's leaf is a dummy
     * name). All file use below happens relative to that handle, so a link swapped in after the path check is refused or
     * ignored, never followed.
     */
    private SecureFiles.Parent openDeploymentDir(UUID hostId, String deploymentName, boolean create) throws IOException {
        deploymentDir(hostId, deploymentName); // up-front validation (traversal, existing symlinks)
        return SecureFiles.openParent(existingBaseDir(), segment(hostId, deploymentName) + "/" + COMPOSE_FILE, create, seam,
                DIR_PERMISSIONS);
    }

    private static void writeAtomically(SecureFiles.Dir dir, String name, String content) throws IOException {
        String temp = name + "." + UUID.randomUUID() + ".tmp";
        try {
            try (SeekableByteChannel channel = dir.createNew(temp, FILE_PERMISSIONS)) {
                ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
            dir.move(temp, name);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(dir, temp);
            throw e instanceof IOException io
                    ? new UncheckedIOException("Could not write " + name, io)
                    : (RuntimeException) e;
        }
    }

    private Optional<String> read(UUID hostId, String deploymentName, String name) {
        try (SecureFiles.Parent parent = openDeploymentDir(hostId, deploymentName, false)) {
            if (!parent.dir().isRegularFile(name)) {
                return Optional.empty();
            }
            return Optional.of(new String(parent.dir().readAll(name, Integer.MAX_VALUE - 8L), StandardCharsets.UTF_8));
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + name, e);
        }
    }

    private static void deleteQuietly(SecureFiles.Dir dir, String name) {
        try {
            dir.deleteFileIfExists(name);
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
