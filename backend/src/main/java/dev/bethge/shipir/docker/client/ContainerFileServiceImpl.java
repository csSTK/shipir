package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.ContainerFileEntry;
import dev.bethge.shipir.docker.dto.ContainerFileWrite;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ContainerFileServiceImpl implements ContainerFileService {

    private static final int MAX_PATH_LENGTH = 4096;
    /** Headroom for tar headers and padding when capping the raw archive of a single file read. */
    private static final long TAR_OVERHEAD = 64 * 1024;
    /** Transfers are bigger than the usual API call; the read timeout alone would cut them off. */
    private static final Duration TRANSFER_EXTRA = Duration.ofMinutes(10);

    private final DockerCallSupport calls;
    private final ContainerDirectoryExecLister execLister;

    public ContainerFileServiceImpl(DockerHostConnectionManager manager, ContainerDirectoryExecLister execLister) {
        this.calls = new DockerCallSupport(manager);
        this.execLister = execLister;
    }

    @Override
    public List<ContainerFileEntry> list(UUID hostId, DockerEndpoint endpoint, String containerId, String path,
                                         long maxScanBytes) {
        String dir = normalizePath(path);
        List<ContainerFileEntry> entries;
        try {
            entries = readDirectory(hostId, endpoint, containerId, dir, maxScanBytes, true);
        } catch (DockerConnectionException e) {
            if (e.getCode() == DockerErrorCode.SIZE_LIMIT_EXCEEDED) {
                // the archive API sends the whole subtree: fall back once to a single-directory exec listing,
                // which throws DIRECTORY_TOO_LARGE itself when exec is not possible
                entries = new ArrayList<>(execLister.list(hostId, endpoint, containerId, dir));
            } else {
                throw e;
            }
        }
        entries.sort(Comparator.comparing((ContainerFileEntry e) -> e.type() != ContainerFileEntry.Type.DIRECTORY)
                .thenComparing(ContainerFileEntry::name));
        return entries;
    }

    private List<ContainerFileEntry> readDirectory(UUID hostId, DockerEndpoint endpoint, String containerId,
                                                   String dir, long maxScanBytes, boolean followLink) {
        LimitedInputStream raw = new LimitedInputStream(openArchive(hostId, endpoint, containerId, dir), maxScanBytes);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(raw)) {
            TarArchiveEntry first = tar.getNextEntry();
            if (first == null) {
                throw new DockerConnectionException(DockerErrorCode.INVALID_PATH, "The path is not a directory.", null);
            }
            if (first.isSymbolicLink() && followLink) {
                return readDirectory(hostId, endpoint, containerId, resolveLink(dir, first.getLinkName()),
                        maxScanBytes, false);
            }
            if (!first.isDirectory()) {
                throw new DockerConnectionException(DockerErrorCode.INVALID_PATH, "The path is not a directory.", null);
            }
            // the first entry is the directory itself, named "<basename>/"
            String prefix = first.getName();
            List<ContainerFileEntry> result = new ArrayList<>();
            for (TarArchiveEntry e = tar.getNextEntry(); e != null; e = tar.getNextEntry()) {
                String name = e.getName();
                if (!name.startsWith(prefix)) continue;
                String rel = name.substring(prefix.length());
                if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
                if (rel.isEmpty() || rel.contains("/")) continue; // only direct children
                result.add(toEntry(dir, rel, e));
            }
            return result;
        } catch (IOException e) {
            throw ioFailure(e, raw);
        }
    }

    private static ContainerFileEntry toEntry(String dir, String name, TarArchiveEntry e) {
        ContainerFileEntry.Type type = e.isDirectory() ? ContainerFileEntry.Type.DIRECTORY
                : e.isSymbolicLink() ? ContainerFileEntry.Type.SYMLINK
                : e.isFile() ? ContainerFileEntry.Type.FILE : ContainerFileEntry.Type.OTHER;
        String path = dir.equals("/") ? "/" + name : dir + "/" + name;
        return new ContainerFileEntry(name, path, type, e.isFile() ? e.getSize() : 0,
                e.getModTime() == null ? null : e.getModTime().toInstant(), e.getMode() & 07777,
                e.isSymbolicLink() ? e.getLinkName() : null);
    }

    @Override
    public InputStream download(UUID hostId, DockerEndpoint endpoint, String containerId, String path, long maxBytes) {
        return new LimitedInputStream(openArchive(hostId, endpoint, containerId, normalizePath(path)), maxBytes);
    }

    InputStream openArchive(UUID hostId, DockerEndpoint endpoint, String containerId, String path) {
        return calls.call(hostId, endpoint, TRANSFER_EXTRA,
                client -> client.copyArchiveFromContainerCmd(containerId, path).exec());
    }

    @Override
    public void upload(UUID hostId, DockerEndpoint endpoint, String containerId, String directory, InputStream tar,
                       long maxBytes) {
        String dir = normalizePath(directory);
        LimitedInputStream limited = new LimitedInputStream(tar, maxBytes) {
            @Override
            public void close() {
                // the caller owns the stream
            }
        };
        try {
            calls.call(hostId, endpoint, TRANSFER_EXTRA, client -> {
                client.copyArchiveToContainerCmd(containerId).withRemotePath(dir).withTarInputStream(limited).exec();
                return null;
            });
        } catch (DockerConnectionException e) {
            if (limited.exceeded()) throw sizeLimit();
            throw e;
        }
    }

    @Override
    public void writeFiles(UUID hostId, DockerEndpoint endpoint, String containerId, String directory,
                           List<ContainerFileWrite> files, long maxBytes) {
        String dir = normalizePath(directory);
        byte[] tar = buildTar(files, maxBytes);
        upload(hostId, endpoint, containerId, dir, new ByteArrayInputStream(tar), Long.MAX_VALUE);
    }

    /** Validates every path and the total size before anything is built or sent. */
    private static byte[] buildTar(List<ContainerFileWrite> files, long maxBytes) {
        Set<String> seen = new HashSet<>();
        long total = 0;
        for (ContainerFileWrite f : files) {
            requireRelativeArchivePath(f.path());
            if (!seen.add(f.path())) {
                throw new DockerConnectionException(DockerErrorCode.INVALID_PATH, "Duplicate file path.", null);
            }
            total += f.content().length;
            if (total > maxBytes) throw sizeLimit();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (ContainerFileWrite f : files) {
                byte[] content = f.content(); // accessor copies; read it once per file
                TarArchiveEntry entry = new TarArchiveEntry(f.path());
                entry.setSize(content.length);
                entry.setMode(f.mode() & 07777);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
            tar.finish();
        } catch (IOException e) {
            throw new UncheckedIOException(e); // in-memory stream, cannot happen
        }
        return out.toByteArray();
    }

    /** Relative path of plain segments: no leading/trailing slash, no empty, {@code .} or {@code ..} segment, no NUL or backslash. */
    private static void requireRelativeArchivePath(String path) {
        boolean bad = path == null || path.isEmpty() || path.length() > MAX_PATH_LENGTH
                || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0;
        if (!bad) {
            for (String part : path.split("/", -1)) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                    bad = true;
                    break;
                }
            }
        }
        if (bad) {
            throw new DockerConnectionException(DockerErrorCode.INVALID_PATH,
                    "File paths must be relative, without '..', empty segments or NUL.", null);
        }
    }

    @Override
    public byte[] readFile(UUID hostId, DockerEndpoint endpoint, String containerId, String path, long maxBytes) {
        String normalized = normalizePath(path);
        // Clamp before adding the headroom: maxBytes + TAR_OVERHEAD overflows for large limits such as Long.MAX_VALUE.
        long archiveLimit = Math.min(maxBytes, Long.MAX_VALUE - TAR_OVERHEAD) + TAR_OVERHEAD;
        LimitedInputStream raw = new LimitedInputStream(openArchive(hostId, endpoint, containerId, normalized),
                archiveLimit);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(raw)) {
            TarArchiveEntry first = tar.getNextEntry();
            if (first == null || !first.isFile()) {
                throw new DockerConnectionException(DockerErrorCode.INVALID_PATH, "The path is not a regular file.", null);
            }
            if (first.getSize() > maxBytes) throw sizeLimit();
            long size = first.getSize();
            if (size > Integer.MAX_VALUE - 8) throw sizeLimit(); // a byte[] cannot hold it
            byte[] content = tar.readNBytes((int) size);
            if (content.length != size) {
                throw new DockerConnectionException(DockerErrorCode.UNREACHABLE,
                        "The archive from the container was truncated.", null);
            }
            return content;
        } catch (IOException e) {
            throw ioFailure(e, raw);
        }
    }

    private DockerConnectionException ioFailure(IOException e, LimitedInputStream raw) {
        if (raw.exceeded()) return sizeLimit();
        return new DockerConnectionException(DockerErrorCode.UNREACHABLE, "The archive from the container could not be read.", e);
    }

    private static DockerConnectionException sizeLimit() {
        return new DockerConnectionException(DockerErrorCode.SIZE_LIMIT_EXCEEDED, "The transfer exceeds the size limit.", null);
    }

    /**
     * Absolute container path without {@code .}, {@code ..} and empty segments; {@code ..} stops at the root.
     * Throws INVALID_PATH for null, relative, NUL-containing or overlong input.
     */
    static String normalizePath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/' || path.indexOf('\0') >= 0
                || path.length() > MAX_PATH_LENGTH) {
            throw new DockerConnectionException(DockerErrorCode.INVALID_PATH, "The path must be an absolute container path.", null);
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                parts.pollLast();
            } else {
                parts.addLast(part);
            }
        }
        return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
    }

    private static String resolveLink(String linkPath, String target) {
        if (target.startsWith("/")) return normalizePath(target);
        int slash = linkPath.lastIndexOf('/');
        String parent = slash <= 0 ? "" : linkPath.substring(0, slash);
        return normalizePath(parent + "/" + target);
    }

    /** Counts bytes and fails with SIZE_LIMIT_EXCEEDED on the first read past the limit. */
    static class LimitedInputStream extends FilterInputStream {

        private final long max;
        private long count;
        private boolean exceeded;

        LimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        boolean exceeded() {
            return exceeded;
        }

        private void add(long n) {
            if (n <= 0) return;
            count += n;
            if (count > max) {
                exceeded = true;
                try {
                    in.close();
                } catch (IOException ignored) {
                    // aborting anyway
                }
                throw sizeLimit();
            }
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) add(1);
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = in.read(buf, off, len);
            add(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(n);
            add(skipped);
            return skipped;
        }
    }
}
