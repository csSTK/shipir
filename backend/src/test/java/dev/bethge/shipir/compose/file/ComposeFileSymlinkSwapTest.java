package dev.bethge.shipir.compose.file;

import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.compose.DeploymentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A symlink swapped in after the path check (TOCTOU) must never be followed by the compose file repository. The
 * {@code seam} runs the swap exactly between the up-front check and the use of the file.
 */
class ComposeFileSymlinkSwapTest {

    private static final UUID HOST = UUID.randomUUID();
    private static final String SECRET = "TOP-SECRET-OUTSIDE";

    @TempDir
    Path tmp;

    private Path base;
    private Path outside;
    private ComposeFileRepository repo;

    @BeforeEach
    void setUp() throws IOException {
        base = tmp.resolve("deployments");
        outside = tmp.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("compose.yaml"), SECRET);
        Files.writeString(outside.resolve(".env"), SECRET);
        repo = new ComposeFileRepository(new DeploymentProperties(base));
        repo.write(HOST, "web", "inside", "A=1");
    }

    private void at(String stage, Runnable action) {
        AtomicBoolean fired = new AtomicBoolean();
        repo.seam = s -> {
            if (s.equals(stage) && fired.compareAndSet(false, true)) {
                action.run();
            }
        };
    }

    private Path dir() {
        return base.resolve(HOST.toString()).resolve("web");
    }

    private void dirToLink() {
        try {
            Files.move(dir(), dir().resolveSibling("web.orig"));
            Files.createSymbolicLink(dir(), outside);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void leafToLink(String name) {
        try {
            Files.delete(dir().resolve(name));
            Files.createSymbolicLink(dir().resolve(name), outside.resolve(name));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertOutsideUntouched() throws IOException {
        assertThat(outside.resolve("compose.yaml")).hasContent(SECRET);
        assertThat(outside.resolve(".env")).hasContent(SECRET);
        try (Stream<Path> s = Files.list(outside)) {
            assertThat(s.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("compose.yaml", ".env");
        }
    }

    @Test
    void readNeverReturnsALeafSwappedForALink() {
        at("leaf:compose.yaml", () -> leafToLink("compose.yaml"));
        Optional<String> result;
        try {
            result = repo.readCompose(HOST, "web");
        } catch (RuntimeException refused) {
            return;
        }
        assertThat(result.orElse("")).doesNotContain(SECRET);
    }

    @Test
    void readRefusesDeploymentDirectorySwappedBeforeItIsOpened() {
        at("open:web", this::dirToLink);
        assertThatThrownBy(() -> repo.readCompose(HOST, "web")).isInstanceOf(PathValidationException.class);
    }

    @Test
    void readEnvNeverLeaksWhenDirectoryIsSwappedAfterItIsOpened() {
        at("leaf:.env", this::dirToLink);
        assertThat(repo.readEnv(HOST, "web")).contains("A=1");
    }

    @Test
    void writeRefusesDirectorySwappedBeforeItIsOpened() throws IOException {
        at("open:web", this::dirToLink);
        assertThatThrownBy(() -> repo.write(HOST, "web", "new", "B=2")).isInstanceOf(PathValidationException.class);
        assertOutsideUntouched();
    }

    @Test
    void writeStaysInTheOpenedDirectoryWhenItIsSwappedForALink() throws IOException {
        at("move:compose.yaml", this::dirToLink);
        repo.write(HOST, "web", "new", "B=2");
        assertOutsideUntouched();
        assertThat(base.resolve(HOST.toString()).resolve("web.orig").resolve("compose.yaml")).hasContent("new");
    }

    @Test
    void writeReplacesALeafLinkInsteadOfWritingThroughIt() throws IOException {
        at("move:compose.yaml", () -> leafToLink("compose.yaml"));
        repo.write(HOST, "web", "new", "B=2");
        assertOutsideUntouched();
        assertThat(dir().resolve("compose.yaml")).hasContent("new");
        assertThat(Files.isSymbolicLink(dir().resolve("compose.yaml"))).isFalse();
    }

    @Test
    void removingTheEnvFileDoesNotFollowADirectorySwap() throws IOException {
        at("delete:.env", this::dirToLink);
        repo.write(HOST, "web", "new", null);
        assertOutsideUntouched();
    }

    @Test
    void deleteManagedFilesDoesNotFollowADirectorySwap() throws IOException {
        at("delete:compose.yaml", this::dirToLink);
        try {
            repo.deleteManagedFiles(HOST, "web");
        } catch (RuntimeException refused) {
            // acceptable: refusing is fine, following is not
        }
        assertOutsideUntouched();
    }

    @Test
    void deleteManagedFilesRefusesDirectorySwappedBeforeItIsOpened() throws IOException {
        at("open:web", this::dirToLink);
        assertThatThrownBy(() -> repo.deleteManagedFiles(HOST, "web")).isInstanceOf(PathValidationException.class);
        assertOutsideUntouched();
    }

    @Test
    void rollbackOverrideIsNotWrittenThroughASwappedDirectory() throws IOException {
        at("move:compose.rollback.yaml", this::dirToLink);
        repo.writeRollbackOverride(HOST, "web", "override");
        assertOutsideUntouched();
    }

    @Test
    void readOfALeafSwappedForAFifoIsAClientErrorNotAHangOrA500() {
        at("leaf:compose.yaml", () -> {
            try {
                Files.delete(dir().resolve("compose.yaml"));
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        new ProcessBuilder("mkfifo", dir().resolve("compose.yaml").toString()).start().waitFor() == 0);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThatThrownBy(() -> repo.readCompose(HOST, "web"))
                .isInstanceOfSatisfying(dev.bethge.shipir.compose.DeploymentException.class,
                        e -> assertThat(e.code()).isEqualTo("NOT_A_FILE"));
    }
}
