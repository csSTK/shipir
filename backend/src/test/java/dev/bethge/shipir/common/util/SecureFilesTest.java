package dev.bethge.shipir.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureFilesTest {

    @TempDir
    Path tmp;

    Path root;
    Path outside;

    @BeforeEach
    void setUp() throws IOException {
        root = tmp.resolve("root");
        outside = tmp.resolve("outside");
        Files.createDirectories(root.resolve("a/b"));
        Files.createDirectories(outside);
        Files.writeString(root.resolve("a/b/f.txt"), "inside");
        Files.writeString(outside.resolve("f.txt"), "SECRET");
    }

    private static void put(SecureFiles.Parent p, String name, String text) throws IOException {
        try (SeekableByteChannel ch = p.dir().createNew(name)) {
            ch.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void readsFileThroughHandles() throws IOException {
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a/b/f.txt", false, null)) {
            assertThat(new String(p.dir().readAll(p.name(), 100), StandardCharsets.UTF_8)).isEqualTo("inside");
        }
    }

    @Test
    void readAllReturnsOneByteMoreThanTheCap() throws IOException {
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a/b/f.txt", false, null)) {
            assertThat(p.dir().readAll(p.name(), 3)).hasSize(4);
        }
    }

    @Test
    void writesMovesAndDeletes() throws IOException {
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a/b/new.txt", false, null)) {
            put(p, "tmp", "x");
            p.dir().move("tmp", "new.txt");
            assertThat(Files.readString(root.resolve("a/b/new.txt"))).isEqualTo("x");
            assertThat(root.resolve("a/b/tmp")).doesNotExist();
            p.dir().deleteFile("new.txt");
            assertThat(root.resolve("a/b/new.txt")).doesNotExist();
        }
    }

    @Test
    void createNewRefusesAnExistingName() throws IOException {
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a/b/f.txt", false, null)) {
            assertThatThrownBy(() -> put(p, "f.txt", "x")).isInstanceOf(FileAlreadyExistsException.class);
        }
    }

    @Test
    void createNewDoesNotWriteThroughALeafLink() throws IOException {
        Files.createSymbolicLink(root.resolve("a/b/link"), outside.resolve("f.txt"));
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a/b/link", false, null)) {
            assertThatThrownBy(() -> put(p, "link", "pwned")).isInstanceOf(java.nio.file.FileSystemException.class);
        }
        assertThat(Files.readString(outside.resolve("f.txt"))).isEqualTo("SECRET");
    }

    @Test
    void moveReplacesALinkAtTheTargetWithoutFollowingIt() throws IOException {
        Files.createSymbolicLink(root.resolve("a/b/link"), outside.resolve("f.txt"));
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a/b/link", false, null)) {
            put(p, "tmp", "x");
            p.dir().move("tmp", "link");
        }
        assertThat(Files.isSymbolicLink(root.resolve("a/b/link"))).isFalse();
        assertThat(Files.readString(outside.resolve("f.txt"))).isEqualTo("SECRET");
    }

    @Test
    void refusesLeafSymlink() throws IOException {
        Files.createSymbolicLink(root.resolve("a/b/link"), outside.resolve("f.txt"));
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a/b/link", false, null)) {
            assertThatThrownBy(() -> p.dir().readAll(p.name(), 100)).isInstanceOf(PathValidationException.class);
        }
    }

    @Test
    void refusesSymlinkedDirectorySegment() throws IOException {
        Files.createSymbolicLink(root.resolve("a/link"), outside);
        assertThatThrownBy(() -> SecureFiles.openParent(root, "a/link/f.txt", false, null))
                .isInstanceOf(PathValidationException.class);
    }

    @Test
    void refusesAfterEachSegmentIsSwappedOnTheWay() throws IOException {
        // swapping a not-yet-opened segment (seam fires before it is opened) is refused
        assertThatThrownBy(() -> SecureFiles.openParent(root, "a/b/f.txt", false, stage -> {
            if (stage.equals("opened:a")) {
                try {
                    Files.move(root.resolve("a/b"), root.resolve("a/b.orig"));
                    Files.createSymbolicLink(root.resolve("a/b"), outside);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        })).isInstanceOf(PathValidationException.class);
    }

    @Test
    void refusesRootThatIsItselfALink() throws IOException {
        Path link = tmp.resolve("rootlink");
        Files.createSymbolicLink(link, root);
        assertThatThrownBy(() -> SecureFiles.openParent(link, "a/b/f.txt", false, null))
                .isInstanceOf(PathValidationException.class);
    }

    @Test
    void allowsRootReachedThroughASymlinkedAncestor() throws IOException {
        Path link = tmp.resolve("ancestorlink");
        Files.createSymbolicLink(link, tmp);
        try (SecureFiles.Parent p = SecureFiles.openParent(link.resolve("root"), "a/b/f.txt", false, null)) {
            assertThat(new String(p.dir().readAll(p.name(), 100), StandardCharsets.UTF_8)).isEqualTo("inside");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "..", "a/../b", "../outside/f.txt", "/etc/passwd", "a//b", "a/./b", "a\\b", "a/b\0c", "."})
    void rejectsMaliciousPaths(String rel) {
        assertThatThrownBy(() -> SecureFiles.openParent(root, rel, false, null)).isInstanceOf(PathValidationException.class);
    }

    @Test
    void missingDirectoryIsNotFoundUnlessCreated() throws IOException {
        assertThatThrownBy(() -> SecureFiles.openParent(root, "x/y/f.txt", false, null)).isInstanceOf(NoSuchFileException.class);
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "x/y/f.txt", true, null)) {
            put(p, "f.txt", "new");
        }
        assertThat(Files.readString(root.resolve("x/y/f.txt"))).isEqualTo("new");
    }

    @Test
    void mkdirDoesNotDescendIntoALinkCreatedMeanwhile() throws IOException {
        assertThatThrownBy(() -> SecureFiles.openParent(root, "x/y/f.txt", true, stage -> {
            if (stage.equals("open:x")) {
                try {
                    Files.createSymbolicLink(root.resolve("x"), outside);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        })).isInstanceOf(PathValidationException.class);
        try (var s = Files.list(outside)) {
            assertThat(s.map(Path::getFileName).map(Path::toString).toList()).containsExactly("f.txt");
        }
    }

    @Test
    void listsAndClassifiesEntries() throws IOException {
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a/x", false, null)) {
            assertThat(p.dir().list()).isEqualTo(List.of("b")).hasSize(1);
            assertThat(p.dir().isDirectory("b")).isTrue();
            assertThat(p.dir().isRegularFile("b")).isFalse();
        }
    }

    // ---- FIFO / device leaf: must never block the caller (shipr-kqbc.75) ----

    /** Runs {@code call} on a daemon thread and fails (instead of hanging the build) if it does not return in time. */
    private static <T> T within(java.time.Duration limit, java.util.concurrent.Callable<T> call) throws Exception {
        java.util.concurrent.CompletableFuture<T> result = new java.util.concurrent.CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                result.complete(call.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        t.setDaemon(true);
        t.start();
        try {
            return result.get(limit.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("call blocked for more than " + limit);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private Path fifo(String name) throws IOException, InterruptedException {
        Path fifo = root.resolve(name);
        Process p = new ProcessBuilder("mkfifo", fifo.toString()).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(p.waitFor() == 0, "mkfifo needed");
        return fifo;
    }

    @Test
    void openReadOfAFifoFailsInsteadOfBlocking() throws Exception {
        fifo("pipe");
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "pipe", false, null)) {
            assertThatThrownBy(() -> within(java.time.Duration.ofSeconds(5), () -> p.dir().openRead(p.name())))
                    .isInstanceOf(SecureFiles.NotRegularFileException.class);
        }
    }

    @Test
    void openReadOfALeafSwappedForAFifoAfterTheTypeCheckFailsInsteadOfBlocking() throws Exception {
        Path leaf = root.resolve("a/b/f.txt");
        Path fifoPath = root.resolve("a/b/pipe-src");
        Process mk = new ProcessBuilder("mkfifo", fifoPath.toString()).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(mk.waitFor() == 0, "mkfifo needed");
        java.util.concurrent.atomic.AtomicBoolean swapped = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<String> seam = stage -> {
            if (stage.equals("leafopen:f.txt") && swapped.compareAndSet(false, true)) {
                try {
                    Files.move(fifoPath, leaf, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        java.time.Duration before = SecureFiles.openTimeout;
        SecureFiles.openTimeout = java.time.Duration.ofMillis(300);
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a/b/f.txt", false, seam)) {
            assertThatThrownBy(() -> within(java.time.Duration.ofSeconds(10), () -> p.dir().openRead(p.name())))
                    .isInstanceOf(SecureFiles.NotRegularFileException.class);
            assertThat(swapped).as("the race window (seam stage) was reached").isTrue();
        } finally {
            SecureFiles.openTimeout = before;
        }
    }

    @Test
    void aBlockedOpenThatCannotBeReleasedStillReturnsAndNeverBlocksCloseOrLaterWork() throws Exception {
        Path leaf = root.resolve("a/b/f.txt");
        Path fifoPath = root.resolve("a/b/pipe-src");
        Process mk = new ProcessBuilder("mkfifo", fifoPath.toString()).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(mk.waitFor() == 0, "mkfifo needed");
        java.util.concurrent.atomic.AtomicBoolean swapped = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<String> seam = stage -> {
            if (stage.equals("leafopen:f.txt") && swapped.compareAndSet(false, true)) {
                try {
                    Files.move(fifoPath, leaf, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                // while the open is blocked on the FIFO, the name is taken over by a regular file, so a path-based
                // release of the blocked reader could never pair with it
                Thread mover = new Thread(() -> {
                    try {
                        Thread.sleep(100);
                        Files.move(leaf, root.resolve("a/b/elsewhere"));
                        Files.writeString(leaf, "regular again");
                    } catch (IOException | InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                });
                mover.setDaemon(true);
                mover.start();
            }
        };
        java.time.Duration before = SecureFiles.openTimeout;
        SecureFiles.openTimeout = java.time.Duration.ofMillis(500);
        try {
            SecureFiles.Parent p = SecureFiles.openParent(root, "a/b/f.txt", false, seam);
            assertThatThrownBy(() -> within(java.time.Duration.ofSeconds(5), () -> p.dir().openRead(p.name())))
                    .isInstanceOf(SecureFiles.NotRegularFileException.class);
            assertThat(swapped).as("the race window (seam stage) was reached").isTrue();
            within(java.time.Duration.ofSeconds(5), () -> {
                p.close();
                return null;
            });
            // unrelated later work on the same tree is not blocked
            try (SecureFiles.Parent q = SecureFiles.openParent(root, "a/b/other.txt", true, null)) {
                within(java.time.Duration.ofSeconds(5), () -> {
                    put(q, "other.txt", "x");
                    return null;
                });
            }
        } finally {
            SecureFiles.openTimeout = before;
        }
    }

    @Test
    void anInterruptAfterTheOpenWasDeliveredClosesTheDeliveredChannel() throws Exception {
        Thread caller = Thread.currentThread();
        java.util.concurrent.atomic.AtomicReference<SeekableByteChannel> delivered = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.function.Consumer<String> seam = stage -> {
            if (stage.equals("leafdelivered:f.txt")) {
                // runs on the opener thread between handing the result over and completing the future
                caller.interrupt();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a/b/f.txt", false, seam)) {
            assertThatThrownBy(() -> p.dir().openRead(p.name())).isInstanceOf(IOException.class)
                    .hasMessageContaining("Interrupted");
            assertThat(Thread.interrupted()).as("interrupt flag restored").isTrue();
            assertThat(SecureFiles.lastDeliveredChannel).as("the delivered channel was closed").isNotNull();
            assertThat(SecureFiles.lastDeliveredChannel.isOpen()).isFalse();
        } finally {
            SecureFiles.lastDeliveredChannel = null;
        }
    }

    @Test
    void abandoningABlockedOpenLogsOneWarnNamingTheFileButNoContent() throws Exception {
        Path leaf = root.resolve("a/b/f.txt");
        Path fifoPath = root.resolve("a/b/pipe-src");
        Process mk = new ProcessBuilder("mkfifo", fifoPath.toString()).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(mk.waitFor() == 0, "mkfifo needed");
        java.util.function.Consumer<String> seam = stage -> {
            if (stage.equals("leafopen:f.txt")) {
                try {
                    Files.move(fifoPath, leaf, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(SecureFiles.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        java.time.Duration before = SecureFiles.openTimeout;
        SecureFiles.openTimeout = java.time.Duration.ofMillis(300);
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a/b/f.txt", false, seam)) {
            assertThatThrownBy(() -> within(java.time.Duration.ofSeconds(10), () -> p.dir().openRead(p.name())))
                    .isInstanceOf(SecureFiles.NotRegularFileException.class);
        } finally {
            SecureFiles.openTimeout = before;
            logger.detachAppender(appender);
        }
        List<ch.qos.logback.classic.spi.ILoggingEvent> warns = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN).toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage()).contains("f.txt").doesNotContain("SECRET");
    }

    @Test
    void openReadOfARegularFileStillWorksUnderTheDeadline() throws Exception {
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a/b/f.txt", false, null);
             SeekableByteChannel ch = p.dir().openRead(p.name())) {
            assertThat(ch.size()).isEqualTo(6);
        }
    }

    // ---- deleteTree / deleteDirectory / lastModified (shipr-kqbc.78) ----

    @Test
    void deleteTreeRemovesNestedContentAndUnlinksSymlinksWithoutFollowing() throws IOException {
        Files.createSymbolicLink(root.resolve("a/b/link-file"), outside.resolve("f.txt"));
        Files.createSymbolicLink(root.resolve("a/link-dir"), outside);
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a", false, null)) {
            p.dir().deleteTree("a");
        }
        assertThat(root.resolve("a")).doesNotExist();
        assertThat(outside.resolve("f.txt")).hasContent("SECRET");
    }

    @Test
    void deleteTreeUnlinksASymlinkAtTheRootOfTheTree() throws IOException {
        Files.createSymbolicLink(root.resolve("lnk"), outside);
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "lnk", false, null)) {
            p.dir().deleteTree("lnk");
        }
        assertThat(Files.exists(root.resolve("lnk"), java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(outside.resolve("f.txt")).hasContent("SECRET");
    }

    @Test
    void deleteTreeOfAMissingEntryIsANoOp() throws IOException {
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "nothing", false, null)) {
            p.dir().deleteTree("nothing");
        }
    }

    @Test
    void deleteTreeNeverFollowsADirectorySwappedForALinkBeforeItIsOpened() throws IOException {
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a", false, stage -> {
            if (stage.equals("opensub:b")) {
                try {
                    Files.move(root.resolve("a/b"), root.resolve("a/b.orig"));
                    Files.createSymbolicLink(root.resolve("a/b"), outside);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        })) {
            try (SecureFiles.Dir a = p.dir().openSubdirectory("a")) {
                assertThatThrownBy(() -> a.deleteTree("b")).isInstanceOf(PathValidationException.class);
            }
        }
        assertThat(outside.resolve("f.txt")).hasContent("SECRET");
    }

    @Test
    void deleteDirectoryOnlyRemovesAnEmptyDirectory() throws IOException {
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "a", false, null)) {
            assertThatThrownBy(() -> p.dir().deleteDirectory("a")).isInstanceOf(java.nio.file.DirectoryNotEmptyException.class);
        }
    }

    @Test
    void lastModifiedDoesNotFollowALink() throws IOException {
        Files.createSymbolicLink(root.resolve("l"), outside.resolve("f.txt"));
        Files.setLastModifiedTime(outside.resolve("f.txt"), java.nio.file.attribute.FileTime.fromMillis(1000));
        try (SecureFiles.Parent p = SecureFiles.openParent(root, "x", false, null)) {
            assertThat(p.dir().lastModified("l").toMillis()).isNotEqualTo(1000);
        }
    }
}
