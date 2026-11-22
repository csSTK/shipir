package dev.bethge.shipir.common.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Symlink-race-free file access below a fixed root, the companion of {@link PathValidationUtil#resolve} (which checks
 * once up front). The root is opened once as a {@link SecureDirectoryStream}; every further step is relative to an
 * already-open directory handle ({@code openat} with {@code O_NOFOLLOW}), so no path is re-resolved after the walk
 * and a symlink swapped in at any point is refused instead of followed. Fails closed: if the platform offers no
 * {@code SecureDirectoryStream}, nothing is opened.
 * <p>
 * Residual: the root itself is opened by path (it is the fixed, Shipir-owned deployment directory; a symlinked
 * ancestor of it is allowed, a symlink as the root itself is refused), and {@code mkdir} of missing directories has to
 * go through a path (the stream API has no mkdir): an empty 0700 directory could end up behind a swapped ancestor, but
 * the created entry is re-opened with NOFOLLOW from the held handle and the walk fails if it is not that directory.
 * <p>
 * The {@code seam} receives stage names ({@code open:<segment>}, {@code opened:<segment>}, {@code leaf:<name>},
 * {@code move:<name>}, {@code delete:<name>}, {@code opensub:<name>}) at the points where a race could strike; tests use it to swap links.
 */
public final class SecureFiles {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecureFiles.class);

    private static final Consumer<String> NO_SEAM = stage -> {
    };

    /** How long opening a regular file for reading may take before it is treated as a blocked FIFO open. */
    static volatile Duration openTimeout = Duration.ofSeconds(2);
    /** Test observation only: the channel most recently handed over by a deadline open. */
    static volatile SeekableByteChannel lastDeliveredChannel;

    /** Deepest directory nesting {@link Dir#deleteTree} descends into (each level holds a file descriptor); deeper is refused. */
    public static final int MAX_DELETE_DEPTH = 64;

    private SecureFiles() {
    }

    /** A tree handed to {@link Dir#deleteTree} is nested deeper than {@link #MAX_DELETE_DEPTH}; nothing below the limit is guaranteed deleted. */
    public static final class TreeTooDeepException extends IOException {
        TreeTooDeepException(String name) {
            super(name + " is nested deeper than " + MAX_DELETE_DEPTH + " directories");
        }
    }

    /** True when the default file system offers {@link SecureDirectoryStream} (Linux, macOS); false e.g. on Windows. */
    public static boolean isSupported() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")))) {
            return stream instanceof SecureDirectoryStream;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The leaf is not a regular file (FIFO, device, socket, directory) and is not opened. */
    public static final class NotRegularFileException extends IOException {
        public NotRegularFileException(String name) {
            this(name, "is not a regular file");
        }

        NotRegularFileException(String name, String reason) {
            super(name + " " + reason);
        }
    }

    /** A directory handle plus the leaf name below it. */
    public record Parent(Dir dir, String name) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            dir.close();
        }
    }

    /**
     * Walks {@code rel} (slash separated, no empty, {@code .} or {@code ..} segment) from {@code root} and returns the
     * held handle of the parent directory and the leaf name. Missing parent directories are created (one segment at a
     * time, with {@code dirAttributes}) when {@code create} is set, otherwise {@link NoSuchFileException} is thrown.
     */
    public static Parent openParent(Path root, String rel, boolean create, Consumer<String> seam,
                                    FileAttribute<?>... dirAttributes) throws IOException {
        Consumer<String> hook = seam == null ? NO_SEAM : seam;
        String[] segments = rel.split("/", -1);
        for (String segment : segments) {
            validateSegment(segment);
        }
        if (Files.isSymbolicLink(root)) {
            throw new PathValidationException("Symbolic links are not allowed");
        }
        Dir dir = openRoot(root, hook);
        Path current = root;
        try {
            for (int i = 0; i < segments.length - 1; i++) {
                String segment = segments[i];
                hook.accept("open:" + segment);
                dir = descend(dir, current, segment, create, dirAttributes);
                current = current.resolve(segment);
                hook.accept("opened:" + segment);
            }
            return new Parent(dir, segments[segments.length - 1]);
        } catch (IOException | RuntimeException e) {
            dir.close();
            throw e;
        }
    }

    private static Dir openRoot(Path root, Consumer<String> hook) throws IOException {
        DirectoryStream<Path> stream = Files.newDirectoryStream(root);
        if (stream instanceof SecureDirectoryStream<Path> sds) {
            return new Dir(sds, hook);
        }
        stream.close();
        throw new PathValidationException("Secure directory access is not supported on this platform");
    }

    /** Opens {@code segment} below {@code parent} without following links; closes {@code parent} on success. */
    private static Dir descend(Dir parent, Path parentPath, String segment, boolean create,
                               FileAttribute<?>[] dirAttributes) throws IOException {
        try {
            return parent.openChild(segment);
        } catch (NoSuchFileException missing) {
            if (!create) {
                parent.close();
                throw missing;
            }
        } catch (IOException | RuntimeException e) {
            parent.close();
            throw e;
        }
        try {
            Files.createDirectory(parentPath.resolve(segment), dirAttributes);
            return parent.openChild(segment);
        } catch (IOException | RuntimeException e) {
            parent.close();
            throw e;
        }
    }

    private static void validateSegment(String segment) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.indexOf('\0') >= 0
                || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) {
            throw new PathValidationException("Path is invalid");
        }
    }

    /** An open directory; all operations are relative to this handle and never follow a symlink. */
    public static final class Dir implements AutoCloseable {
        private final SecureDirectoryStream<Path> sds;
        private final Consumer<String> seam;
        /** Set when a blocked open was given up on: the opener thread owns (and closes) the handle, see openReadWithDeadline. */
        private volatile boolean abandoned;

        private Dir(SecureDirectoryStream<Path> sds, Consumer<String> seam) {
            this.sds = sds;
            this.seam = seam;
        }

        /** Opens a child directory (NOFOLLOW) and closes this handle. A link or non-directory is refused. */
        private Dir openChild(String segment) throws IOException {
            validateSegment(segment);
            try {
                SecureDirectoryStream<Path> child = sds.newDirectoryStream(Path.of(segment), LinkOption.NOFOLLOW_LINKS);
                close();
                return new Dir(child, seam);
            } catch (NoSuchFileException e) {
                throw e;
            } catch (IOException e) {
                throw new PathValidationException("Symbolic links are not allowed");
            }
        }

        public BasicFileAttributes attributes(String name) throws IOException {
            validateSegment(name);
            BasicFileAttributeView view = sds.getFileAttributeView(Path.of(name), BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            return view.readAttributes();
        }

        public boolean isRegularFile(String name) {
            try {
                return attributes(name).isRegularFile();
            } catch (IOException e) {
                return false;
            }
        }

        public boolean isDirectory(String name) {
            try {
                return attributes(name).isDirectory();
            } catch (IOException e) {
                return false;
            }
        }

        /**
         * Opens a regular file for reading. Anything else (FIFO, device, socket, directory) is refused with
         * {@link NotRegularFileException}: opening a FIFO for reading blocks until a writer shows up, which would hold the
         * calling request thread (and any lock it holds) forever. The type is checked without following links first;
         * since Java cannot open non-blocking, the open itself also runs under a deadline, so a file swapped for a FIFO
         * between the check and the open (the seam stage {@code leafopen:<name>}) still fails instead of hanging.
         */
        public SeekableByteChannel openRead(String name) throws IOException {
            validateSegment(name);
            seam.accept("leaf:" + name);
            BasicFileAttributes attrs = attributes(name);
            if (attrs.isSymbolicLink()) {
                throw new PathValidationException("Symbolic links are not allowed");
            }
            if (!attrs.isRegularFile()) {
                throw new NotRegularFileException(name);
            }
            seam.accept("leafopen:" + name);
            return openReadWithDeadline(name);
        }

        private static final int PENDING = 0;
        private static final int DELIVERED = 1;
        private static final int ABANDONED = 2;

        /**
         * Runs the open on a daemon thread and waits for it at most {@code openTimeout}. The JDK's stream holds its read
         * lock during the blocking {@code openat} and {@code close()} needs the write lock, so a caller that gave up
         * could never close this handle. Instead, ownership is handed off: on timeout the caller marks this directory
         * abandoned and returns at once without closing it ({@link #close()} then does nothing); the opener thread
         * closes the handle, and any late channel, as soon as its {@code openat} returns (if ever).
         */
        private SeekableByteChannel openReadWithDeadline(String name) throws IOException {
            CompletableFuture<SeekableByteChannel> opened = new CompletableFuture<>();
            AtomicInteger state = new AtomicInteger(PENDING);
            Thread opener = new Thread(() -> {
                SeekableByteChannel channel = null;
                Throwable failure = null;
                try {
                    channel = openLeaf(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                } catch (Throwable e) {
                    failure = e;
                }
                if (state.compareAndSet(PENDING, DELIVERED)) {
                    lastDeliveredChannel = channel;
                    seam.accept("leafdelivered:" + name);
                    if (failure != null) {
                        opened.completeExceptionally(failure);
                    } else {
                        opened.complete(channel);
                    }
                    return;
                }
                // the caller gave up: this thread owns the handle now
                try {
                    if (channel != null) {
                        channel.close();
                    }
                    sds.close();
                } catch (IOException | RuntimeException ignored) {
                    // nobody is waiting for the result
                }
            }, "secure-open");
            opener.setDaemon(true);
            opener.start();
            try {
                return opened.get(openTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (state.compareAndSet(PENDING, ABANDONED)) {
                    abandoned = true;
                    log.warn("Abandoned a blocked open of {} after {} ms (likely a FIFO); one daemon thread and file "
                            + "descriptor stay parked until it returns", name, openTimeout.toMillis());
                    throw new NotRegularFileException(name, "was not opened in time (blocked, likely a FIFO)");
                }
                return deliveredResult(opened); // the opener finished just now
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (state.compareAndSet(PENDING, ABANDONED)) {
                    abandoned = true;
                    log.warn("Abandoned a blocked open of {} (caller interrupted); one daemon thread and file "
                            + "descriptor stay parked until it returns", name);
                } else {
                    closeDelivered(opened); // delivered just before the interrupt: nobody else will close it
                }
                throw new IOException("Interrupted while opening " + name, e);
            } catch (ExecutionException e) {
                throw unwrap(e);
            }
        }

        /** Waits (uninterruptibly, the opener already finished) for a delivered result and closes it. */
        private static void closeDelivered(CompletableFuture<SeekableByteChannel> opened) {
            try {
                SeekableByteChannel channel = opened.join();
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException | RuntimeException ignored) {
                // failed open or failed close: nothing left to release
            }
        }

        private static SeekableByteChannel deliveredResult(CompletableFuture<SeekableByteChannel> opened) throws IOException {
            try {
                return opened.get();
            } catch (ExecutionException e) {
                throw unwrap(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }

        private static IOException unwrap(ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                return io;
            }
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            return new IOException(e.getCause());
        }

        /** Creates a new file (fails if the name exists, never follows a link). */
        public SeekableByteChannel createNew(String name, FileAttribute<?>... attributes) throws IOException {
            validateSegment(name);
            return openLeaf(name, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS), attributes);
        }

        private SeekableByteChannel openLeaf(String name, Set<java.nio.file.OpenOption> options,
                                             FileAttribute<?>... attributes) throws IOException {
            try {
                return sds.newByteChannel(Path.of(name), options, attributes);
            } catch (NoSuchFileException | java.nio.file.FileAlreadyExistsException e) {
                throw e;
            } catch (IOException e) {
                if (isSymlink(name)) {
                    throw new PathValidationException("Symbolic links are not allowed");
                }
                throw e;
            }
        }

        private boolean isSymlink(String name) {
            try {
                return attributes(name).isSymbolicLink();
            } catch (IOException e) {
                return false;
            }
        }

        /** Reads at most {@code maxBytes + 1} bytes (the extra byte tells the caller the cap was exceeded). */
        public byte[] readAll(String name, long maxBytes) throws IOException {
            try (SeekableByteChannel channel = openRead(name)) {
                long limit = maxBytes + 1;
                ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(limit, 1 << 16));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                while (out.size() < limit && channel.read(buffer) >= 0) {
                    buffer.flip();
                    int take = (int) Math.min(buffer.remaining(), limit - out.size());
                    out.write(buffer.array(), 0, take);
                    buffer.clear();
                }
                return out.toByteArray();
            }
        }

        /** Streams the content of {@code name} into {@code target}, a channel from {@code targetDir.createNew}. */
        public void copyTo(String name, SeekableByteChannel target) throws IOException {
            try (SeekableByteChannel in = openRead(name)) {
                ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
                while (in.read(buffer) >= 0) {
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        target.write(buffer);
                    }
                    buffer.clear();
                }
            }
        }

        /** Atomic rename within this directory ({@code renameat}); replaces an existing entry, never follows it. */
        public void move(String from, String to) throws IOException {
            validateSegment(from);
            validateSegment(to);
            seam.accept("move:" + to);
            sds.move(Path.of(from), sds, Path.of(to));
        }

        public void deleteFile(String name) throws IOException {
            validateSegment(name);
            seam.accept("delete:" + name);
            sds.deleteFile(Path.of(name));
        }

        public void deleteFileIfExists(String name) throws IOException {
            try {
                deleteFile(name);
            } catch (NoSuchFileException ignored) {
                // already gone
            }
        }

        /** Removes an empty directory entry without following it; {@link java.nio.file.DirectoryNotEmptyException} otherwise. */
        public void deleteDirectory(String name) throws IOException {
            validateSegment(name);
            seam.accept("deletedir:" + name);
            sds.deleteDirectory(Path.of(name));
        }

        /**
         * Removes the entry {@code name} below this handle: a directory with everything in it (at most {@link #MAX_DELETE_DEPTH} levels deep, else {@link TreeTooDeepException}), anything else (a symlink
         * included) as a single unlink. Every directory is opened relative to its held parent with NOFOLLOW, so a link
         * swapped in for a directory is refused ({@link PathValidationException}), never followed. A missing entry is a
         * no-op.
         */
        public void deleteTree(String name) throws IOException {
            deleteTree(name, 1);
        }

        private void deleteTree(String name, int depth) throws IOException {
            validateSegment(name);
            BasicFileAttributes attrs;
            try {
                attrs = attributes(name);
            } catch (NoSuchFileException gone) {
                return;
            }
            if (!attrs.isDirectory()) {
                deleteFileIfExists(name);
                return;
            }
            if (depth > MAX_DELETE_DEPTH) {
                throw new TreeTooDeepException(name);
            }
            try (Dir sub = openSubdirectory(name)) {
                for (String child : sub.list()) {
                    sub.deleteTree(child, depth + 1);
                }
            }
            try {
                deleteDirectory(name);
            } catch (NoSuchFileException ignored) {
                // already gone
            }
        }

        /** Last modification time of the entry itself (a symlink is not followed). */
        public java.nio.file.attribute.FileTime lastModified(String name) throws IOException {
            return attributes(name).lastModifiedTime();
        }

        /** Entry names of this directory (can be called once per handle). */
        public List<String> list() throws IOException {
            List<String> names = new ArrayList<>();
            for (Path p : sds) {
                names.add(p.getFileName().toString());
            }
            return names;
        }

        /** Opens a sub-directory of this one as a separate handle; this handle stays open. */
        public Dir openSubdirectory(String name) throws IOException {
            validateSegment(name);
            seam.accept("opensub:" + name);
            try {
                return new Dir(sds.newDirectoryStream(Path.of(name), LinkOption.NOFOLLOW_LINKS), seam);
            } catch (NoSuchFileException e) {
                throw e;
            } catch (IOException e) {
                throw new PathValidationException("Symbolic links are not allowed");
            }
        }

        @Override
        public void close() throws IOException {
            if (abandoned) {
                return; // the opener thread closes it when its blocked openat returns
            }
            sds.close();
        }
    }
}
