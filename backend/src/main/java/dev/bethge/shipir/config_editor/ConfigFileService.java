package dev.bethge.shipir.config_editor;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.common.util.PathValidationUtil;
import dev.bethge.shipir.common.util.SecureFiles;
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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.NoSuchFileException;
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
import java.util.function.Consumer;
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
    /** Test seam: receives the stages of {@link SecureFiles} (where a symlink swap could strike); null = none. */
    Consumer<String> seam;

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
        deployments.requireAdminIfSystemManaged(hostId, id);
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
        return deployments.whileNoRunInProgress(id, () -> io("Could not write file", () -> {
            try (SecureFiles.Parent p = open(t, true)) {
                backupIfExists(t, p);
                writeAtomically(p, content);
                return response(t, p);
            }
        }));
    }

    @Auditable(action = "CONFIG_FILE_DELETE", targetType = "CONFIG_FILE",
            targetId = "T(dev.bethge.shipir.config_editor.ConfigFileService).auditTarget(#id, #path)")
    public void delete(UUID hostId, UUID id, String path) {
        Target t = target(hostId, id, path, true);
        requireFile(t.file());
        deployments.whileNoRunInProgress(id, () -> io("Could not delete file", () -> {
            try (SecureFiles.Parent p = open(t, false)) {
                backupIfExists(t, p);
                p.dir().deleteFile(p.name());
                return null;
            }
        }));
    }

    public List<ConfigFileBackup> backups(UUID hostId, UUID id, String path) {
        Target t = target(hostId, id, path, false);
        try (SecureFiles.Parent b = openBackupParent(t, "x", false)) {
            return b.dir().list().stream().filter(n -> n.endsWith(".bak") && b.dir().isRegularFile(n))
                    .sorted(Comparator.reverseOrder())
                    .map(n -> backupOf(b.dir(), n))
                    .toList();
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (PathValidationException e) {
            throw symlinkRefused();
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
        return deployments.whileNoRunInProgress(id, () -> io("Could not restore file", () -> {
            try (SecureFiles.Parent b = openBackupParent(t, backupId + ".bak", false);
                 SecureFiles.Parent p = open(t, true)) {
                String content = decode(b.dir().readAll(b.name(), properties.maxFileBytes()));
                backupIfExists(t, p);
                writeAtomically(p, content);
                return response(t, p);
            }
        }));
    }

    // ---- path handling ----

    private record Target(Path root, Path file, String rel) {
    }

    private Target target(UUID hostId, UUID id, String path, boolean forWrite) {
        if (forWrite) {
            deployments.requireOperate(hostId, id);
        } else {
            deployments.requireView(hostId, id);
            deployments.requireAdminIfSystemManaged(hostId, id);
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

    private interface Io<T> {
        T run() throws IOException;
    }

    /** Runs a handle-based file operation and maps its failures to the API errors (links refused, missing file). */
    private static <T> T io(String message, Io<T> operation) {
        try {
            return operation.run();
        } catch (PathValidationException e) {
            throw symlinkRefused();
        } catch (NoSuchFileException e) {
            throw new ConfigFileException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found");
        } catch (SecureFiles.NotRegularFileException e) {
            throw new ConfigFileException(HttpStatus.BAD_REQUEST, "NOT_A_FILE", "Path is not a regular file");
        } catch (IOException e) {
            throw new UncheckedIOException(message, e);
        }
    }

    private static ConfigFileException symlinkRefused() {
        return new ConfigFileException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "Symbolic links are not allowed");
    }

    /** Parent directory handle of the target, walked from the deployment root without following any link. */
    private SecureFiles.Parent open(Target t, boolean create) throws IOException {
        return SecureFiles.openParent(t.root(), t.rel(), create, seam, DIR_PERMISSIONS);
    }

    private SecureFiles.Parent openBackupParent(Target t, String leaf, boolean create) throws IOException {
        return SecureFiles.openParent(t.root(), BACKUP_DIR + "/" + backupHash(t) + "/" + leaf, create, seam, DIR_PERMISSIONS);
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

    /** Strict UTF-8 text within the size cap ({@code bytes} may hold one byte past the cap, see {@code readAll}). */
    private String decode(byte[] bytes) {
        if (bytes.length > properties.maxFileBytes()) {
            throw tooLarge();
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0) {
                throw new CharacterCodingException();
            }
            return text;
        } catch (CharacterCodingException e) {
            throw new ConfigFileException(HttpStatus.BAD_REQUEST, "NOT_TEXT", "Only UTF-8 text files are supported");
        }
    }

    private ConfigFileResponse response(Target t) {
        return io("Could not read file", () -> {
            try (SecureFiles.Parent p = open(t, false)) {
                return response(t, p);
            }
        });
    }

    private ConfigFileResponse response(Target t, SecureFiles.Parent p) throws IOException {
        byte[] bytes = p.dir().readAll(p.name(), properties.maxFileBytes());
        String content = decode(bytes);
        BasicFileAttributes attrs = p.dir().attributes(p.name());
        return new ConfigFileResponse(t.rel(), content, bytes.length, attrs.lastModifiedTime().toInstant(), readOnly(t.rel()));
    }

    /** Temp file next to the target (created NOFOLLOW), then {@code renameat} over it; a link at the target is replaced, not followed. */
    private static void writeAtomically(SecureFiles.Parent p, String content) throws IOException {
        String temp = ".shipir-" + UUID.randomUUID() + ".tmp";
        try {
            try (SeekableByteChannel out = p.dir().createNew(temp, FILE_PERMISSIONS)) {
                ByteBuffer bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    out.write(bytes);
                }
            }
            p.dir().move(temp, p.name());
        } catch (IOException | RuntimeException e) {
            try {
                p.dir().deleteFileIfExists(temp);
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
    }

    // ---- backups ----

    private static String backupHash(Target t) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(t.rel().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Path form of the backup directory, only for the existence pre-checks; all real access goes through handles. */
    private Path backupDir(Target t) {
        return t.root().resolve(BACKUP_DIR).resolve(backupHash(t));
    }

    /** Streams the current content into a new .bak (handle to handle, no memory copy), then prunes to the newest N. */
    private void backupIfExists(Target t, SecureFiles.Parent source) throws IOException {
        if (!source.dir().isRegularFile(source.name())) {
            return;
        }
        String backupId = ID_TIME.format(clock.instant()) + "-" + "%08x".formatted(ThreadLocalRandom.current().nextInt());
        try (SecureFiles.Parent b = openBackupParent(t, backupId + ".bak", true)) {
            try (SeekableByteChannel out = b.dir().createNew(b.name(), FILE_PERMISSIONS)) {
                source.dir().copyTo(source.name(), out);
            } catch (IOException | RuntimeException e) {
                b.dir().deleteFileIfExists(b.name());
                throw e;
            }
            // a directory listing taken after the create may not show the new entry, so add it explicitly
            List<String> all = Stream.concat(b.dir().list().stream(), Stream.of(b.name())).distinct()
                    .filter(n -> n.endsWith(".bak")).sorted(Comparator.reverseOrder()).toList();
            for (int i = properties.backupsPerFile(); i < all.size(); i++) {
                b.dir().deleteFileIfExists(all.get(i));
            }
        }
    }

    private ConfigFileBackup backupOf(SecureFiles.Dir dir, String name) {
        try {
            BasicFileAttributes attrs = dir.attributes(name);
            return new ConfigFileBackup(name.substring(0, name.length() - ".bak".length()),
                    attrs.lastModifiedTime().toInstant(), attrs.size());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read backup", e);
        }
    }
}
