package dev.bethge.shipir.compose.file;

import dev.bethge.shipir.common.util.PathValidationUtil;
import dev.bethge.shipir.common.util.SecureFiles;
import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.compose.DeploymentProperties;
import org.springframework.http.HttpStatus;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

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
    /**
     * Per-host directory of the Git staging areas {@code <base>/<hostId>/.staging/<deploymentName>/} (same layout as a
     * deployment directory: {@code compose.yaml}, {@code .env}, {@code repo/}). A deployment name never starts with a dot,
     * so a staging directory cannot collide with a deployment directory, and it lies outside every deployment directory:
     * the config editor, the graph hash and compose never see it.
     */
    public static final String STAGING_DIR = ".staging";
    /** Every file this repository writes into a deployment directory (other files there are not Shipir-approved). */
    public static final Set<String> SHIPIR_WRITTEN_FILES = Set.of(COMPOSE_FILE, ENV_FILE, ROLLBACK_OVERRIDE_FILE);
    /**
     * Largest UTF-8 size of a {@code compose.yaml} or {@code .env} that {@link #write} stores and {@link #readCompose}/
     * {@link #readEnv} return (bytes). Same default as the config editor's {@code shipir.config-editor.max-file-bytes}
     * (1 MiB). Not configurable: these files are read whole into memory on many paths and no operator need for a larger one
     * has come up. Larger content fails with 413 FILE_TOO_LARGE, on write before anything touches the disk.
     */
    public static final long MAX_FILE_BYTES = 1024 * 1024;

    private static final FileAttribute<Set<PosixFilePermission>> DIR_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> FILE_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ComposeFileRepository.class);

    private final Path baseDir;

    /** Test seam: receives the stages of {@link SecureFiles} (where a symlink swap could strike); null = none. */
    Consumer<String> seam;

    public ComposeFileRepository(DeploymentProperties properties) {
        this.baseDir = properties.baseDir().toAbsolutePath().normalize();
    }

    /** Writes compose.yaml and, if {@code envContent} is non-null, .env; a null {@code envContent} removes an existing .env. */
    public void write(UUID hostId, String deploymentName, String composeContent, String envContent) {
        deploymentDir(hostId, deploymentName); // up-front validation (traversal, existing symlinks)
        writeIn(segment(hostId, deploymentName), composeContent, envContent);
    }

    /** Like {@link #write} but into the Git staging area of the deployment (see {@link #STAGING_DIR}). */
    public void writeStaged(UUID hostId, String deploymentName, String composeContent, String envContent) {
        stagingDir(hostId, deploymentName); // up-front validation (traversal, existing symlinks)
        writeIn(stagingSegment(hostId, deploymentName), composeContent, envContent);
    }

    private void writeIn(String dirSegment, String composeContent, String envContent) {
        // checked before the disk is touched: a file that read() would refuse must never be stored
        requireWithinCap(COMPOSE_FILE, composeContent);
        if (envContent != null) {
            requireWithinCap(ENV_FILE, envContent);
        }
        try (SecureFiles.Parent parent = openDir(dirSegment, true)) {
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
        requireWithinCap(ROLLBACK_OVERRIDE_FILE, content);
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

    /**
     * Removes the whole deployment directory including a Git checkout in {@code repo/}. The tree is walked and deleted
     * through held no-follow directory handles ({@link SecureFiles.Dir#deleteTree}): symlinks are unlinked, never
     * followed, and a directory swapped for a link while deleting is refused.
     */
    public void delete(UUID hostId, String deploymentName) {
        deploymentDir(hostId, deploymentName); // up-front validation (traversal, existing symlinks)
        deleteTree(segment(hostId, deploymentName), "Could not delete deployment directory");
        discardStaging(hostId, deploymentName);
    }

    /** Removes the Git staging area of the deployment (no-op if there is none), links unlinked and never followed. */
    public void discardStaging(UUID hostId, String deploymentName) {
        stagingDir(hostId, deploymentName); // up-front validation
        deleteTree(stagingSegment(hostId, deploymentName), "Could not delete the staging directory");
        deleteTree(previousSegment(hostId, deploymentName), "Could not delete the replaced checkout");
    }

    /**
     * Copies everything the live deployment directory holds besides what a pull replaces or Shipir writes itself
     * ({@code repo/}, {@code compose.yaml}, {@code .env}, the rollback override, the config editor's backups) into the
     * staging area, so the staged tree is what the live directory will contain after promotion: the review shows those files
     * and the staged graph hash covers them. Links are recreated as links with the same text, never followed; the entry and
     * size caps are the graph hash's (a directory over them cannot be approved anyway: 413 DEPLOYMENT_DIR_TOO_LARGE).
     */
    public void copyLiveFilesToStaging(UUID hostId, String deploymentName) {
        Path live = deploymentDir(hostId, deploymentName);
        Path staging = stagingDir(hostId, deploymentName);
        if (!Files.isDirectory(live, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        long[] budget = {ComposeGraphHasher.MAX_BYTES, ComposeGraphHasher.MAX_ENTRIES};
        try {
            Files.walkFileTree(live, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs)
                        throws IOException {
                    if (dir.equals(live)) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    if (skipped(live, dir)) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    count();
                    Files.createDirectories(staging.resolve(live.relativize(dir)), DIR_PERMISSIONS);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                        throws IOException {
                    if (skipped(live, file)) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    count();
                    Path target = staging.resolve(live.relativize(file));
                    if (attrs.isSymbolicLink()) {
                        Files.createSymbolicLink(target, Files.readSymbolicLink(file));
                    } else if (attrs.isRegularFile()) {
                        if ((budget[0] -= attrs.size()) < 0) {
                            throw tooLargeDir();
                        }
                        Files.copy(file, target, java.nio.file.LinkOption.NOFOLLOW_LINKS); // a source swapped for a link is copied as that link, never followed
                    } else {
                        throw tooLargeDir(); // a socket, FIFO or device: nothing that can be reviewed or approved
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                private void count() {
                    if (--budget[1] < 0) {
                        throw tooLargeDir();
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not copy the deployment files into the staging area", e);
        }
    }

    private static boolean skipped(Path live, Path path) {
        Path rel = live.relativize(path);
        String top = rel.getName(0).toString();
        return top.equals(REPO_DIR) || top.equals(".shipir-backups")
                || (rel.getNameCount() == 1 && SHIPIR_WRITTEN_FILES.contains(top));
    }

    private static DeploymentException tooLargeDir() {
        return new DeploymentException(HttpStatus.CONTENT_TOO_LARGE, "DEPLOYMENT_DIR_TOO_LARGE",
                "The deployment directory is too large or holds unsupported files and cannot be reviewed");
    }

    private void deleteTree(String dirSegment, String failure) {
        try (SecureFiles.Parent parent = SecureFiles.openParent(existingBaseDir(), dirSegment, false, seam)) {
            parent.dir().deleteTree(parent.name());
        } catch (NoSuchFileException missing) {
            // nothing there, nothing to delete
        } catch (IOException e) {
            throw new UncheckedIOException(failure, e);
        }
    }

    /**
     * Makes the staged checkout the live one: the current {@code repo/} (if any) is renamed away, the staged one renamed
     * into its place (both on the same file system, so each step is atomic), then the rest of the staging area is removed.
     * If the second rename fails the first is undone. The deployment's {@code compose.yaml} and {@code .env} are not touched.
     *
     * @throws IllegalStateException if there is no staged checkout
     */
    public void promoteStagedRepo(UUID hostId, String deploymentName) {
        Path staged = stagingRepoDir(hostId, deploymentName);
        Path live = repoDir(hostId, deploymentName);
        // a sibling of the staging dir, not inside it: the staged tree holds copies of live entries, any name of which may occur
        Path previous = previousDir(hostId, deploymentName);
        if (!Files.isDirectory(staged, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("No staged checkout to promote");
        }
        try {
            boolean hadLive = Files.exists(live, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (hadLive) {
                Files.move(live, previous, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            try {
                Files.move(staged, live, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                if (hadLive) {
                    Files.move(previous, live, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not promote the staged checkout", e);
        }
        try {
            discardStaging(hostId, deploymentName);
        } catch (RuntimeException e) {
            log.warn("Could not remove the staging area of deployment {} after promoting it: {}", deploymentName, e.toString());
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

    /** Validated Git staging directory {@code <base>/<hostId>/.staging/<name>} (may not exist); never inside a deployment directory. */
    public Path stagingDir(UUID hostId, String deploymentName) {
        return PathValidationUtil.resolve(existingBaseDir(), stagingSegment(hostId, deploymentName));
    }

    /** Validated checkout location inside the staging directory ({@code <stagingDir>/repo}, may not exist). */
    public Path stagingRepoDir(UUID hostId, String deploymentName) {
        return PathValidationUtil.resolve(existingBaseDir(), stagingSegment(hostId, deploymentName) + "/" + REPO_DIR);
    }

    /** Validated location of the compose file inside the staged checkout (same rules as {@link #repoComposeFile}). */
    public Path stagingRepoComposeFile(UUID hostId, String deploymentName, String composePath) {
        return PathValidationUtil.resolve(stagingRepoDir(hostId, deploymentName), composePath);
    }

    /** Where {@link #promoteStagedRepo} parks the replaced checkout: {@code <hostId>/.staging/<name>.previous}, beside the staging dir (a name has no dot, so it never clashes with one). */
    private Path previousDir(UUID hostId, String deploymentName) {
        return PathValidationUtil.resolve(existingBaseDir(), previousSegment(hostId, deploymentName));
    }

    private static String previousSegment(UUID hostId, String deploymentName) {
        return stagingSegment(hostId, deploymentName) + ".previous";
    }

    private static String stagingSegment(UUID hostId, String deploymentName) {
        if (hostId == null) {
            throw new IllegalArgumentException("hostId is required");
        }
        return hostId + "/" + STAGING_DIR + "/" + PathValidationUtil.validateDeploymentName(deploymentName);
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
        return openDir(segment(hostId, deploymentName), create);
    }

    private SecureFiles.Parent openDir(String dirSegment, boolean create) throws IOException {
        return SecureFiles.openParent(existingBaseDir(), dirSegment + "/" + COMPOSE_FILE, create, seam, DIR_PERMISSIONS);
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
            // readAll returns at most MAX_FILE_BYTES + 1 bytes, so one extra byte reveals an oversized file
            byte[] bytes = parent.dir().readAll(name, MAX_FILE_BYTES);
            if (bytes.length > MAX_FILE_BYTES) {
                throw tooLarge(name);
            }
            return Optional.of(new String(bytes, StandardCharsets.UTF_8));
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (SecureFiles.NotRegularFileException e) {
            // swapped for a FIFO/device after the type check: a client-visible conflict, not a server error
            throw new DeploymentException(HttpStatus.BAD_REQUEST, "NOT_A_FILE", "The deployment file is not a regular file");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + name, e);
        }
    }

    /**
     * Refuses content whose UTF-8 size exceeds {@link #MAX_FILE_BYTES}. Counted in bytes, not characters: the API's
     * character limits allow several MB of multibyte text, which read() would then refuse (a self-lock).
     */
    private static void requireWithinCap(String name, String content) {
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            throw tooLarge(name);
        }
    }

    private static DeploymentException tooLarge(String name) {
        return new DeploymentException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                "The deployment file " + name + " is larger than " + MAX_FILE_BYTES + " bytes");
    }

    private static void deleteQuietly(SecureFiles.Dir dir, String name) {
        try {
            dir.deleteFileIfExists(name);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
