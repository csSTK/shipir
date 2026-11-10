package dev.bethge.shipir.common.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Central path traversal protection. All file access built from user input resolves through here.
 */
public final class PathValidationUtil {

    private static final Pattern DEPLOYMENT_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");
    private static final Pattern PERCENT_ENCODING = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[a-zA-Z]:.*");

    private PathValidationUtil() {
    }

    /**
     * Resolves {@code relativePath} against {@code baseDir} and returns the normalized (not symlink-resolved) path.
     * Rejects empty, absolute, NUL, backslash, percent-encoded and {@code ..} input, and any result whose real
     * location (nearest existing ancestor, symlinks followed) lies outside the real base directory.
     */
    public static Path resolve(Path baseDir, String relativePath) {
        if (baseDir == null) {
            throw new PathValidationException("Base directory is required");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new PathValidationException("Path must not be empty");
        }
        if (relativePath.indexOf('\0') >= 0) {
            throw new PathValidationException("Path contains a NUL character");
        }
        if (relativePath.indexOf('\\') >= 0) {
            throw new PathValidationException("Path contains a backslash");
        }
        if (PERCENT_ENCODING.matcher(relativePath).find()) {
            throw new PathValidationException("Path contains percent-encoding");
        }
        if (relativePath.startsWith("/") || DRIVE_PREFIX.matcher(relativePath).matches()) {
            throw new PathValidationException("Absolute paths are not allowed");
        }
        for (String segment : relativePath.split("/", -1)) {
            if (segment.equals("..")) {
                throw new PathValidationException("Path traversal is not allowed");
            }
        }
        try {
            Path base = baseDir.toAbsolutePath().normalize();
            Path realBase = base.toRealPath();
            Path candidate = base.resolve(relativePath).normalize();
            if (!candidate.startsWith(base)) {
                throw new PathValidationException("Path escapes the base directory");
            }
            Path existing = candidate;
            while (!Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (!existing.toRealPath().startsWith(realBase)) {
                throw new PathValidationException("Path escapes the base directory");
            }
            return candidate;
        } catch (InvalidPathException | IOException e) {
            throw new PathValidationException("Path is invalid");
        }
    }

    /**
     * Use-time symlink guard, to be called right before (and again after) touching {@code file}: no component below
     * {@code root} may be a symbolic link, and the nearest existing ancestor must still resolve inside the real
     * {@code root}. {@link #resolve} runs once up front; this closes the gap in which a link could be swapped in
     * between that check and the actual file operation. Fails closed with {@link PathValidationException}.
     */
    public static void requireNoSymlinks(Path root, Path file) {
        try {
            Path base = root.toAbsolutePath().normalize();
            Path target = file.toAbsolutePath().normalize();
            if (!target.startsWith(base)) {
                throw new PathValidationException("Path escapes the base directory");
            }
            Path current = base;
            for (Path segment : base.relativize(target)) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) {
                    throw new PathValidationException("Symbolic links are not allowed");
                }
            }
            Path existing = target;
            while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (!existing.toRealPath().startsWith(base.toRealPath())) {
                throw new PathValidationException("Path escapes the base directory");
            }
        } catch (IOException e) {
            throw new PathValidationException("Path is invalid");
        }
    }

    /**
     * Reads at most {@code maxBytes + 1} bytes of {@code file} without following a symlink at use time: the file is
     * opened with {@code NOFOLLOW_LINKS} and the path is verified with {@link #requireNoSymlinks} before and after
     * opening. The extra byte lets the caller detect "larger than the cap" without reading the whole file.
     */
    public static byte[] readNoFollow(Path root, Path file, long maxBytes) throws IOException {
        requireNoSymlinks(root, file);
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            requireNoSymlinks(root, file);
            long limit = maxBytes + 1;
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(limit, 1 << 16));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            while (out.size() < limit && channel.read(buffer) >= 0) {
                buffer.flip();
                int take = (int) Math.min(buffer.remaining(), limit - out.size());
                out.write(buffer.array(), 0, take);
                buffer.clear();
            }
            return out.toByteArray();
        } catch (java.nio.file.FileSystemException e) {
            requireNoSymlinks(root, file); // a link opened with NOFOLLOW_LINKS fails with ELOOP: report it as such
            throw e;
        }
    }

    /**
     * Validates a deployment name (also used as Compose project name and directory segment): {@code [a-z0-9][a-z0-9_-]{0,62}}.
     */
    public static String validateDeploymentName(String name) {
        if (name == null || !DEPLOYMENT_NAME.matcher(name).matches()) {
            throw new PathValidationException("Invalid deployment name");
        }
        return name;
    }
}
