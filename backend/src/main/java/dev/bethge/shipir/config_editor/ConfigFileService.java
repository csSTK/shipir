package dev.bethge.shipir.config_editor;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.common.util.PathValidationUtil;
import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.compose.DeploymentService;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.config_editor.dto.ConfigFileBackup;
import dev.bethge.shipir.config_editor.dto.ConfigFileEntry;
import dev.bethge.shipir.config_editor.dto.ConfigFileResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lists, reads, writes, deletes and restores free-form config files inside a managed deployment's directory. Every path
 * goes through {@link PathValidationUtil}; symlinks anywhere on the path are refused. {@code compose.yaml}, {@code .env}
 * and the backup directory at the top level are off limits, the Git checkout {@code repo/} is read-only. Each write,
 * delete and restore copies the current content into {@code .shipir-backups/<sha256(path)>/<id>.bak} first and keeps only
 * the newest N copies per file. Env-like files ({@link EnvLikeFiles}) are listed in the tree but their content is only
 * readable with OPERATE access. File contents are never logged or audited.
 */
@Service
@EnableConfigurationProperties(ConfigFileProperties.class)
public class ConfigFileService {

    static final String BACKUP_DIR = ".shipir-backups";
    private static final int MAX_TREE_ENTRIES = 5000;
    private static final Pattern BACKUP_ID = Pattern.compile("\\d{17}-[0-9a-f]{8}");
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);
    private static final FileAttribute<Set<PosixFilePermission>> DIR_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<Set<PosixFilePermission>> FILE_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private final DeploymentService deployments;
    private final ConfigFileProperties properties;
    private final Clock clock;

    ConfigFileService(DeploymentService deployments, ConfigFileProperties properties, Clock clock) {
        this.deployments = deployments;
        this.properties = properties;
        this.clock = clock;
    }

    /** Audit target {@code <deploymentId>:<path>}: control characters dropped, capped to the column size. */
    public static String auditTarget(UUID id, String path) {
        String clean = path == null ? "" : path.replaceAll("\\p{Cntrl}", "");
        String target = id + ":" + clean;
        return target.length() <= 255 ? target : target.substring(0, 255);
    }

    public List<ConfigFileEntry> tree(UUID hostId, UUID id) {
        deployments.requireView(hostId, id);
        Path dir = deployments.directoryOf(hostId, id);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<ConfigFileEntry> entries = new ArrayList<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (d.equals(dir)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = relative(dir, d);
                    if (hidden(rel) || attrs.isSymbolicLink()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    entries.add(new ConfigFileEntry(rel, true, 0, attrs.lastModifiedTime().toInstant(), readOnly(rel)));
                    return entries.size() >= MAX_TREE_ENTRIES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    String rel = relative(dir, f);
                    if (attrs.isRegularFile() && !hidden(rel)) {
                        entries.add(new ConfigFileEntry(rel, false, attrs.size(), attrs.lastModifiedTime().toInstant(), readOnly(rel)));
                    }
                    return entries.size() >= MAX_TREE_ENTRIES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list deployment files", e);
        }
        entries.sort(Comparator.comparing(ConfigFileEntry::path));
        return entries;
    }

    public ConfigFileResponse read(UUID hostId, UUID id, String path) {
        Target t = target(hostId, id, path, false);
        requireEnvReadAccess(hostId, id, t.rel());
        requireFile(t.file());
        return response(t);
    }

    /**
     * Like {@link #read} but for callers that must never see secrets (the AI assistant): env-like files are refused
     * with the same error as for a viewer, whatever the caller's access level.
     */
    public ConfigFileResponse readWithoutSecrets(UUID hostId, UUID id, String path) {
        Target t = target(hostId, id, path, false);
        if (EnvLikeFiles.matches(t.rel())) {
            throw new ConfigFileException(HttpStatus.FORBIDDEN, "ENV_FILE_RESTRICTED",
                    "Environment and secret files cannot be read here");
        }
        requireFile(t.file());
        return response(t);
    }

    @Auditable(action = "CONFIG_FILE_WRITE", targetType = "CONFIG_FILE",
            targetId = "T(dev.bethge.shipir.config_editor.ConfigFileService).auditTarget(#id, #path)")
    public ConfigFileResponse write(UUID hostId, UUID id, String path, String content) {
        Target t = target(hostId, id, path, true);
        checkContent(content);
        if (Files.isDirectory(t.file(), LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigFileException(HttpStatus.BAD_REQUEST, "NOT_A_FILE", "Path is a directory");
        }
        backupIfExists(t);
        writeAtomically(t.file(), content);
        return response(t);
    }

    @Auditable(action = "CONFIG_FILE_DELETE", targetType = "CONFIG_FILE",
            targetId = "T(dev.bethge.shipir.config_editor.ConfigFileService).auditTarget(#id, #path)")
    public void delete(UUID hostId, UUID id, String path) {
        Target t = target(hostId, id, path, true);
        requireFile(t.file());
        backupIfExists(t);
        try {
            Files.delete(t.file());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete file", e);
        }
    }

    public List<ConfigFileBackup> backups(UUID hostId, UUID id, String path) {
        Target t = target(hostId, id, path, false);
        Path dir = backupDir(t);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".bak"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .map(this::backupOf)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list backups", e);
        }
    }

    @Auditable(action = "CONFIG_FILE_RESTORE", targetType = "CONFIG_FILE",
            targetId = "T(dev.bethge.shipir.config_editor.ConfigFileService).auditTarget(#id, #path)")
    public ConfigFileResponse restore(UUID hostId, UUID id, String path, String backupId) {
        Target t = target(hostId, id, path, true);
        if (backupId == null || !BACKUP_ID.matcher(backupId).matches()) {
            throw new ConfigFileException(HttpStatus.BAD_REQUEST, "INVALID_BACKUP_ID", "Invalid backup id");
        }
        Path backup = backupDir(t).resolve(backupId + ".bak");
        if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigFileException(HttpStatus.NOT_FOUND, "BACKUP_NOT_FOUND", "Backup not found");
        }
        if (Files.isDirectory(t.file(), LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigFileException(HttpStatus.BAD_REQUEST, "NOT_A_FILE", "Path is a directory");
        }
        String content = decode(backup);
        backupIfExists(t);
        writeAtomically(t.file(), content);
        return response(t);
    }

    // ---- path handling ----

    private record Target(Path root, Path file, String rel) {
    }

    private Target target(UUID hostId, UUID id, String path, boolean forWrite) {
        if (forWrite) {
            deployments.requireOperate(hostId, id);
        } else {
            deployments.requireView(hostId, id);
        }
        Path root = deployments.directoryOf(hostId, id);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!forWrite) {
                throw new ConfigFileException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found");
            }
            try {
                Files.createDirectories(root, DIR_PERMISSIONS);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not create deployment directory", e);
            }
        }
        Path resolved;
        try {
            resolved = PathValidationUtil.resolve(root, path);
        } catch (PathValidationException e) {
            throw new ConfigFileException(HttpStatus.BAD_REQUEST, "INVALID_PATH", e.getMessage());
        }
        String rel = relative(root, resolved);
        if (rel.isEmpty() || hidden(rel)) {
            throw new ConfigFileException(HttpStatus.BAD_REQUEST, "EXCLUDED_PATH",
                    "This path is managed by Shipir and cannot be edited here");
        }
        rejectSymlinks(root, rel);
        if (forWrite && readOnly(rel)) {
            throw new ConfigFileException(HttpStatus.FORBIDDEN, "READ_ONLY_PATH", "The Git checkout is read-only");
        }
        return new Target(root, resolved, rel);
    }

    /** Top-level compose.yaml and .env (case-insensitive, as the file system may be), the backup dir, and .git inside repo/. */
    private static boolean hidden(String rel) {
        String first = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
        String lower = first.toLowerCase(Locale.ROOT);
        if (rel.equals(first) && (lower.equals(ComposeFileRepository.COMPOSE_FILE) || lower.equals(ComposeFileRepository.ENV_FILE))) {
            return true;
        }
        return lower.equals(BACKUP_DIR) || (lower.equals(ComposeFileRepository.REPO_DIR) && inGitDir(rel));
    }

    private static boolean inGitDir(String rel) {
        for (String segment : rel.split("/")) {
            if (segment.equals(".git")) {
                return true;
            }
        }
        return false;
    }

    /** Env-like files (see {@link EnvLikeFiles}) are readable only with OPERATE access: view-only users get 403. */
    private void requireEnvReadAccess(UUID hostId, UUID id, String rel) {
        if (!EnvLikeFiles.matches(rel)) {
            return;
        }
        try {
            deployments.requireOperate(hostId, id);
        } catch (DeploymentException e) {
            if (e.status() == HttpStatus.FORBIDDEN) {
                throw new ConfigFileException(HttpStatus.FORBIDDEN, "ENV_FILE_RESTRICTED",
                        "Environment and secret files can only be read with operate access");
            }
            throw e;
        }
    }

    private static boolean readOnly(String rel) {
        return rel.equalsIgnoreCase(ComposeFileRepository.REPO_DIR)
                || rel.toLowerCase(Locale.ROOT).startsWith(ComposeFileRepository.REPO_DIR + "/");
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /** No segment of the path may be a symlink: a link could point to compose.yaml, .env or into repo/. */
    private static void rejectSymlinks(Path root, String rel) {
        Path current = root;
        for (String segment : rel.split("/")) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new ConfigFileException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Symbolic links are not allowed");
            }
        }
    }

    private static void requireFile(Path file) {
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigFileException(HttpStatus.BAD_REQUEST, "NOT_A_FILE", "Path is a directory");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigFileException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found");
        }
    }

    // ---- content ----

    private void checkContent(String content) {
        if (content.indexOf('\0') >= 0) {
            throw new ConfigFileException(HttpStatus.BAD_REQUEST, "NOT_TEXT", "Only UTF-8 text files are supported");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > properties.maxFileBytes()) {
            throw tooLarge();
        }
    }

    private ConfigFileException tooLarge() {
        return new ConfigFileException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                "File is larger than " + properties.maxFileBytes() + " bytes");
    }

    /** Reads a file as strict UTF-8 text within the size cap. */
    private String decode(Path file) {
        try {
            if (Files.size(file) > properties.maxFileBytes()) {
                throw tooLarge();
            }
            byte[] bytes = Files.readAllBytes(file);
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0) {
                throw new CharacterCodingException();
            }
            return text;
        } catch (CharacterCodingException e) {
            throw new ConfigFileException(HttpStatus.BAD_REQUEST, "NOT_TEXT", "Only UTF-8 text files are supported");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read file", e);
        }
    }

    private ConfigFileResponse response(Target t) {
        String content = decode(t.file());
        try {
            return new ConfigFileResponse(t.rel(), content, Files.size(t.file()),
                    Files.getLastModifiedTime(t.file()).toInstant(), readOnly(t.rel()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read file", e);
        }
    }

    private static void writeAtomically(Path target, String content) {
        Path temp = null;
        try {
            Files.createDirectories(target.getParent(), DIR_PERMISSIONS);
            temp = Files.createTempFile(target.getParent(), ".shipir-", ".tmp", FILE_PERMISSIONS);
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
            throw new UncheckedIOException("Could not write file", e);
        }
    }

    // ---- backups ----

    private Path backupDir(Target t) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(t.rel().getBytes(StandardCharsets.UTF_8));
            return t.root().resolve(BACKUP_DIR).resolve(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void backupIfExists(Target t) {
        if (!Files.isRegularFile(t.file(), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path dir = backupDir(t);
        String backupId = ID_TIME.format(clock.instant()) + "-" + "%08x".formatted(ThreadLocalRandom.current().nextInt());
        try {
            Files.createDirectories(dir.getParent(), DIR_PERMISSIONS);
            Files.createDirectories(dir, DIR_PERMISSIONS);
            Path backup = dir.resolve(backupId + ".bak");
            Files.copy(t.file(), backup);
            Files.setPosixFilePermissions(backup, PosixFilePermissions.fromString("rw-------"));
            prune(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create backup", e);
        }
    }

    private void prune(Path dir) throws IOException {
        List<Path> all;
        try (Stream<Path> stream = Files.list(dir)) {
            all = stream.filter(p -> p.getFileName().toString().endsWith(".bak"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        }
        for (int i = properties.backupsPerFile(); i < all.size(); i++) {
            Files.deleteIfExists(all.get(i));
        }
    }

    private ConfigFileBackup backupOf(Path p) {
        String name = p.getFileName().toString();
        try {
            return new ConfigFileBackup(name.substring(0, name.length() - ".bak".length()),
                    Files.getLastModifiedTime(p).toInstant(), Files.size(p));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read backup", e);
        }
    }
}
