package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a tar before it is extracted into a volume, so nothing in it can reach outside the volume root:
 * no absolute names, no {@code ..} segments, no symlink or hard link leaving the root (absolute target, or enough
 * {@code ..} to climb above it), no entry written through an earlier symlink, no devices or FIFOs, bounded size.
 * Entry names and link targets over 4096 characters or 128 segments are refused as INVALID_ARCHIVE, so the work per
 * entry stays small however large the archive is.
 */
final class VolumeArchiveValidator {

    /** Entry names and link targets longer than this many characters or segments are refused (bounds the work per entry). */
    private static final int MAX_PATH_LENGTH = 4096;
    private static final int MAX_SEGMENTS = 128;

    private VolumeArchiveValidator() {
    }

    /** Reads the whole archive. Throws INVALID_ARCHIVE or SIZE_LIMIT_EXCEEDED; the caller closes {@code tar}. */
    static void validate(InputStream tar, long maxBytes) {
        ContainerFileServiceImpl.LimitedInputStream limited = new ContainerFileServiceImpl.LimitedInputStream(tar, maxBytes) {
            @Override
            public void close() {
                // the caller owns the stream
            }
        };
        try (TarArchiveInputStream in = new TarArchiveInputStream(limited)) {
            TarArchiveEntry entry = in.getNextEntry();
            if (entry == null) {
                throw invalid("The archive contains no entries.");
            }
            long total = 0;
            Set<String> symlinks = new HashSet<>();
            for (; entry != null; entry = in.getNextEntry()) {
                List<String> parts = segments(entry.getName());
                rejectWriteThroughSymlink(parts, symlinks);
                if (entry.isSymbolicLink()) {
                    resolveTarget(entry.getLinkName(), parts.subList(0, Math.max(0, parts.size() - 1)), symlinks, false);
                    symlinks.add(String.join("/", parts));
                } else if (entry.isLink()) {
                    resolveTarget(entry.getLinkName(), List.of(), symlinks, true);
                    // conservative: a hard link may be a second name of a symlink, so nothing may be written below it
                    symlinks.add(String.join("/", parts));
                } else if (entry.isDirectory()) {
                    // nothing more to check
                } else if (entry.isFile() && !entry.isCharacterDevice() && !entry.isBlockDevice() && !entry.isFIFO()) {
                    total += Math.max(0, entry.getSize());
                    if (total > maxBytes) {
                        throw new DockerConnectionException(DockerErrorCode.SIZE_LIMIT_EXCEEDED,
                                "The transfer exceeds the size limit.", null);
                    }
                } else {
                    throw invalid("The archive contains a special file.");
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            if (limited.exceeded()) {
                throw new DockerConnectionException(DockerErrorCode.SIZE_LIMIT_EXCEEDED, "The transfer exceeds the size limit.", null);
            }
            throw new DockerConnectionException(DockerErrorCode.INVALID_ARCHIVE, "The upload is not a valid tar archive.", e);
        }
    }

    /** Entry name as path segments below the root; rejects absolute names, NUL and any {@code ..} segment. */
    private static List<String> segments(String name) {
        if (name == null || name.indexOf('\0') >= 0 || name.startsWith("/")) {
            throw invalid("The archive contains an absolute or malformed entry name.");
        }
        String[] raw = boundedSplit(name);
        List<String> parts = new ArrayList<>();
        for (String part : raw) {
            if (part.equals("..")) {
                throw invalid("The archive contains an entry that leaves the volume.");
            }
            if (!part.isEmpty() && !part.equals(".")) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static void rejectWriteThroughSymlink(List<String> parts, Set<String> symlinks) {
        if (symlinks.isEmpty()) {
            return;
        }
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.size() - 1; i++) {
            if (i > 0) {
                prefix.append('/');
            }
            prefix.append(parts.get(i));
            if (symlinks.contains(prefix.toString())) {
                throw invalid("The archive writes through a symbolic link.");
            }
        }
    }

    /** Splits a path after checking its length; refuses more than {@value #MAX_SEGMENTS} segments. */
    private static String[] boundedSplit(String path) {
        if (path.length() > MAX_PATH_LENGTH) {
            throw invalid("The archive contains an entry name or link target that is too long.");
        }
        String[] raw = path.split("/");
        if (raw.length > MAX_SEGMENTS) {
            throw invalid("The archive contains an entry name or link target that is too deep.");
        }
        return raw;
    }

    /**
     * Resolves a link target lexically from {@code base} (the entry's directory; the root for hard links). It must be
     * relative, never climb above the root and never continue past an earlier link (a {@code ..} or further segment
     * after a symlink would be resolved by the filesystem, not lexically). A hard link must not target a link itself.
     */
    private static void resolveTarget(String target, List<String> base, Set<String> links, boolean hardLink) {
        if (target == null || target.isEmpty() || target.indexOf('\0') >= 0 || target.startsWith("/")) {
            throw invalid("The archive contains a link with an absolute or empty target.");
        }
        List<String> path = new ArrayList<>(base);
        for (String part : boundedSplit(target)) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (!links.isEmpty() && !path.isEmpty() && links.contains(String.join("/", path))) {
                throw invalid("The archive contains a link that passes through a symbolic link.");
            }
            if (part.equals("..")) {
                if (path.isEmpty()) {
                    throw invalid("The archive contains a link that leaves the volume.");
                }
                path.remove(path.size() - 1);
            } else {
                path.add(part);
            }
        }
        if (hardLink && links.contains(String.join("/", path))) {
            throw invalid("The archive contains a hard link to a symbolic link.");
        }
    }

    private static DockerConnectionException invalid(String message) {
        return new DockerConnectionException(DockerErrorCode.INVALID_ARCHIVE, message, null);
    }
}
