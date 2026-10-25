package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Packs a cloned repository into a tar build context. Skips every {@code .git} directory (top-level and nested, e.g. a vendored repository) and everything that is
 * not a regular file or directory (symlinks are not followed and not archived, so a hostile repository cannot pull host
 * files into the context). The content size is checked while writing. {@code .dockerignore} is not interpreted.
 */
final class BuildContextArchiver {

    private BuildContextArchiver() {
    }

    /** Writes {@code root} to {@code target}; throws SIZE_LIMIT_EXCEEDED when the content passes {@code maxBytes}. */
    static void archive(Path root, Path target, long maxBytes) throws IOException {
        try (OutputStream out = Files.newOutputStream(target);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            long[] total = {0};
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (dir.getFileName().toString().equals(".git")) {
                        return FileVisitResult.SKIP_SUBTREE; // top-level or vendored: Git metadata is never build input
                    }
                    TarArchiveEntry entry = new TarArchiveEntry(name(dir) + "/");
                    entry.setMode(0755);
                    tar.putArchiveEntry(entry);
                    tar.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                        return FileVisitResult.CONTINUE;
                    }
                    total[0] += attrs.size();
                    if (total[0] > maxBytes) {
                        throw new DockerConnectionException(DockerErrorCode.SIZE_LIMIT_EXCEEDED,
                                "The build context exceeds the size limit.", null);
                    }
                    TarArchiveEntry entry = new TarArchiveEntry(name(file));
                    entry.setSize(attrs.size());
                    entry.setMode(Files.isExecutable(file) ? 0755 : 0644);
                    tar.putArchiveEntry(entry);
                    Files.copy(file, tar);
                    tar.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }

                private String name(Path p) {
                    return root.relativize(p).toString().replace('\\', '/');
                }
            });
            tar.finish();
        }
    }
}
