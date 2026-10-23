package dev.bethge.shipir.common.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Central path traversal protection. All file access built from user input resolves through here.
 */
public final class PathValidationUtil {

    private static final Pattern STACK_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");
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
     * Validates a stack name (also used as Compose project name and directory segment): {@code [a-z0-9][a-z0-9_-]{0,62}}.
     */
    public static String validateStackName(String name) {
        if (name == null || !STACK_NAME.matcher(name).matches()) {
            throw new PathValidationException("Invalid stack name");
        }
        return name;
    }
}
