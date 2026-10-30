package dev.bethge.shipir.compose.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Fingerprint of everything compose can read from a deployment directory: compose file, {@code .env}, include / extends /
 * env_file targets and a Git checkout. An ADMIN's deploy stores it with the version; a system run may stay unrestricted only
 * while the directory still hashes to the same value.
 *
 * <p>Only {@code <dir>/repo/.git} (Shipir's own Git metadata, hidden from the config editor) and the rollback override
 * (rewritten per run) are skipped; every other {@code .git}, which an OPERATOR can write, is hashed. Files are streamed, and
 * the total size is capped: a directory over the cap (a big bind-mounted data dir) yields empty. Symlinks are hashed by
 * their link text; one whose resolved target is outside the directory (or does not exist) yields empty, so nothing
 * outside the approved tree can be vouched for. Any I/O problem yields empty: the caller must treat that as "not approved".
 *
 * <p>Known residual risk: the hash is checked before the compose process starts, so a file changed in between is not
 * noticed (same window as the earlier "only Shipir's own files" rule).
 */
public final class ComposeGraphHasher {

    /** Total bytes hashed per directory before the hash fails closed. */
    public static final long MAX_BYTES = 256L * 1024 * 1024;

    private static final String GIT_DIR = ".git";

    private ComposeGraphHasher() {
    }

    public static Optional<String> hash(Path dir) {
        return hash(dir, MAX_BYTES);
    }

    public static Optional<String> hash(Path dir, long maxBytes) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(digestOf(new byte[0]));
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            Path realRoot = dir.toRealPath();
            long[] budget = {maxBytes};
            update(md, dir, dir, realRoot, budget);
            return Optional.of(HexFormat.of().formatHex(md.digest()));
        } catch (IOException | RuntimeException | NoSuchAlgorithmException e) {
            return Optional.empty();
        }
    }

    private static void update(MessageDigest md, Path root, Path current, Path realRoot, long[] budget) throws IOException {
        List<Path> children;
        try (Stream<Path> s = Files.list(current)) {
            children = s.sorted().toList();
        }
        for (Path p : children) {
            String name = p.getFileName().toString();
            boolean top = current.equals(root);
            if (top && name.equals(ComposeFileRepository.ROLLBACK_OVERRIDE_FILE)) {
                continue;
            }
            String rel = root.relativize(p).toString();
            if (Files.isSymbolicLink(p)) {
                if (!p.toRealPath().startsWith(realRoot)) {
                    throw new IOException("symlink leaves the directory: " + rel); // also dangling links (toRealPath throws)
                }
                put(md, "L", rel, Files.readSymbolicLink(p).toString().getBytes(StandardCharsets.UTF_8));
            } else if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                if (name.equals(GIT_DIR) && current.equals(root.resolve("repo"))) {
                    continue;
                }
                put(md, "D", rel, new byte[0]);
                update(md, root, p, realRoot, budget);
            } else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                putFile(md, rel, p, budget);
            } else {
                throw new IOException("unsupported file type: " + rel);
            }
        }
    }

    private static void putFile(MessageDigest md, String rel, Path file, long[] budget) throws IOException {
        long size = Files.size(file);
        budget[0] -= size;
        if (budget[0] < 0) {
            throw new IOException("directory too large to fingerprint");
        }
        header(md, "F", rel, size);
        long read = 0;
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                read += n;
                if (read > size) {
                    throw new IOException("file changed while hashing: " + rel);
                }
                md.update(buf, 0, n);
            }
        }
        if (read != size) {
            throw new IOException("file changed while hashing: " + rel);
        }
    }

    private static void put(MessageDigest md, String kind, String rel, byte[] content) {
        header(md, kind, rel, content.length);
        md.update(content);
    }

    /** Length-prefixed fields so no two different trees can produce the same byte stream. */
    private static void header(MessageDigest md, String kind, String rel, long contentLength) {
        byte[] relBytes = rel.getBytes(StandardCharsets.UTF_8);
        md.update(kind.getBytes(StandardCharsets.UTF_8));
        md.update(HexFormat.of().parseHex(String.format("%016x", relBytes.length)));
        md.update(relBytes);
        md.update(HexFormat.of().parseHex(String.format("%016x", contentLength)));
    }

    private static String digestOf(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
