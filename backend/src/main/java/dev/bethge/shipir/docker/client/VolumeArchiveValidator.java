package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks a tar before it is extracted into a volume, so nothing in it can reach outside the volume root:
 * no absolute names, no {@code ..} segments, no symlink or hard link leaving the root (absolute target, or enough
 * {@code ..} to climb above it), no entry written through an earlier symlink, no devices or FIFOs, bounded size.
 * Entry names and link targets over 4096 characters or 128 segments are refused as INVALID_ARCHIVE, so the work per
 * entry stays small however large the archive is.
 *
 * <p>Link bookkeeping lives in one trie keyed by path segments, so every check costs O(segments) lookups and the
 * work per entry is linear in its name/target length. Forward references are refused: a link target is checked
 * lexically when its entry is read, so it must not rely on a directory that a LATER entry could turn into a link
 * (e.g. {@code s -> sub/..} followed by {@code sub -> .}). The sound and simple rule is that every path a link target
 * continues through (a segment follows it, {@code ..} included) is remembered as "traversed", and any link entry
 * created later at such a path is refused. The final path of a target is not traversed, so link-to-link chains
 * ({@code t -> s}) stay valid.
 *
 * <p>The trie is bounded: nodes are created only for link entry names and the segments walked by link targets (never
 * for files or directories), and at most {@value #MAX_TRIE_NODES} nodes in total, else INVALID_ARCHIVE. Without the
 * cap a 100 MB upload of links with long novel paths could build millions of nodes (gigabytes of heap). At roughly
 * 200 bytes per node the cap costs about 40 MB, while real volumes (even OS-like trees with tens of thousands of links
 * sharing their directory prefixes) stay far below it.
 */
final class VolumeArchiveValidator {

    /** Entry names and link targets longer than this many characters or segments are refused (bounds the work per entry). */
    private static final int MAX_PATH_LENGTH = 4096;
    private static final int MAX_SEGMENTS = 128;
    /** Upper bound for the link trie, see the class comment. */
    static final int MAX_TRIE_NODES = 200_000;

    private VolumeArchiveValidator() {
    }

    /** Reads the whole archive. Throws INVALID_ARCHIVE or SIZE_LIMIT_EXCEEDED; the caller closes {@code tar}. */
    static void validate(InputStream tar, long maxBytes) {
        validate(tar, maxBytes, MAX_TRIE_NODES);
    }

    /** Test seam: same as {@link #validate(InputStream, long)} with a custom trie node cap. */
    static void validate(InputStream tar, long maxBytes, int maxTrieNodes) {
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
            Links links = new Links(maxTrieNodes);
            for (; entry != null; entry = in.getNextEntry()) {
                List<String> parts = segments(entry.getName());
                rejectWriteThroughSymlink(parts, links);
                if (entry.isSymbolicLink()) {
                    resolveTarget(entry.getLinkName(), parts.subList(0, Math.max(0, parts.size() - 1)), links, false);
                    markLink(parts, links);
                } else if (entry.isLink()) {
                    resolveTarget(entry.getLinkName(), List.of(), links, true);
                    // conservative: a hard link may be a second name of a symlink, so nothing may be written below it
                    markLink(parts, links);
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

    private static void rejectWriteThroughSymlink(List<String> parts, Links links) {
        Node node = links.root;
        for (int i = 0; i < parts.size() - 1; i++) {
            node = node.children.get(parts.get(i));
            if (node == null) {
                return;
            }
            if (node.link) {
                throw invalid("The archive writes through a symbolic link.");
            }
        }
    }

    /** Records a link entry; refused when an earlier link target already continued through this path. */
    private static void markLink(List<String> parts, Links links) {
        Node node = links.root;
        for (String part : parts) {
            node = links.child(node, part);
        }
        if (node.traversed) {
            throw invalid("The archive contains a link that an earlier link target passes through.");
        }
        node.link = true;
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
    private static void resolveTarget(String target, List<String> base, Links links, boolean hardLink) {
        if (target == null || target.isEmpty() || target.indexOf('\0') >= 0 || target.startsWith("/")) {
            throw invalid("The archive contains a link with an absolute or empty target.");
        }
        String[] segments = boundedSplit(target);
        Deque<Node> path = new ArrayDeque<>();
        Node root = links.root;
        path.push(root);
        for (String part : base) {
            Node dir = links.child(path.peek(), part);
            // the entry's own directory chain is passed through lexically too (d/e/l -> .. relies on d)
            dir.traversed = true;
            path.push(dir);
        }
        for (String part : segments) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            Node current = path.peek();
            if (current != root) {
                if (current.link) {
                    throw invalid("The archive contains a link that passes through a symbolic link.");
                }
                current.traversed = true;
            }
            if (part.equals("..")) {
                if (path.size() == 1) {
                    throw invalid("The archive contains a link that leaves the volume.");
                }
                path.pop();
            } else {
                path.push(links.child(current, part));
            }
        }
        if (hardLink && path.peek().link) {
            throw invalid("The archive contains a hard link to a symbolic link.");
        }
    }

    /** The link trie with its node budget. */
    private static final class Links {
        final Node root = new Node();
        private final int maxNodes;
        private int nodes;

        Links(int maxNodes) {
            this.maxNodes = maxNodes;
        }

        Node child(Node parent, String part) {
            Node existing = parent.children.get(part);
            if (existing != null) {
                return existing;
            }
            if (++nodes > maxNodes) {
                throw invalid("The archive contains too many link path segments.");
            }
            Node created = new Node();
            parent.children.put(part, created);
            return created;
        }
    }

    /** One path segment: {@code link} = a link entry has this name, {@code traversed} = a link target continued through it. */
    private static final class Node {
        final Map<String, Node> children = new HashMap<>();
        boolean link;
        boolean traversed;
    }

    private static DockerConnectionException invalid(String message) {
        return new DockerConnectionException(DockerErrorCode.INVALID_ARCHIVE, message, null);
    }
}
