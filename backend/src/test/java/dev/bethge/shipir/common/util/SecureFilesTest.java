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
}
