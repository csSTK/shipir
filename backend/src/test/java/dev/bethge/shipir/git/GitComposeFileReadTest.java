package dev.bethge.shipir.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading the compose file out of an attacker-influenced checkout: a link swapped in after the check is never followed. */
class GitComposeFileReadTest {

    private static final String SECRET = "TOP-SECRET-OUTSIDE";

    @TempDir
    Path tmp;

    private Path repo;
    private Path outside;

    @BeforeEach
    void setUp() throws IOException {
        repo = tmp.resolve("repo");
        outside = tmp.resolve("outside");
        Files.createDirectories(repo.resolve("deploy"));
        Files.createDirectories(outside);
        Files.writeString(repo.resolve("deploy/compose.yaml"), "services: {}\n");
        Files.writeString(outside.resolve("compose.yaml"), SECRET);
    }

    private Consumer<String> at(String stage, Runnable action) {
        AtomicBoolean fired = new AtomicBoolean();
        return s -> {
            if (s.equals(stage) && fired.compareAndSet(false, true)) {
                action.run();
            }
        };
    }

    private void dirToLink() {
        try {
            Files.move(repo.resolve("deploy"), repo.resolve("deploy.orig"));
            Files.createSymbolicLink(repo.resolve("deploy"), outside);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void leafToLink() {
        try {
            Files.delete(repo.resolve("deploy/compose.yaml"));
            Files.createSymbolicLink(repo.resolve("deploy/compose.yaml"), outside.resolve("compose.yaml"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void readsTheFileInsideTheCheckout() {
        assertThat(GitRepositoryService.readComposeFile(repo, "deploy/compose.yaml", null)).isEqualTo("services: {}\n");
    }

    @Test
    void refusesALeafSwappedForALink() {
        assertThatThrownBy(() -> GitRepositoryService.readComposeFile(repo, "deploy/compose.yaml",
                at("leaf:compose.yaml", this::leafToLink)))
                .isInstanceOfSatisfying(GitOperationException.class, e -> assertThat(e.getMessage()).doesNotContain(SECRET));
    }

    @Test
    void refusesADirectorySwappedForALinkBeforeItIsOpened() {
        assertThatThrownBy(() -> GitRepositoryService.readComposeFile(repo, "deploy/compose.yaml",
                at("open:deploy", this::dirToLink)))
                .isInstanceOf(GitOperationException.class);
    }

    @Test
    void readsTheOpenedDirectoryWhenItIsSwappedAfterwards() {
        assertThat(GitRepositoryService.readComposeFile(repo, "deploy/compose.yaml",
                at("leaf:compose.yaml", this::dirToLink))).isEqualTo("services: {}\n");
    }

    @Test
    void reportsAMissingFile() {
        assertThatThrownBy(() -> GitRepositoryService.readComposeFile(repo, "deploy/nope.yaml", null))
                .isInstanceOfSatisfying(GitOperationException.class, e -> assertThat(e.code()).isEqualTo("COMPOSE_FILE_NOT_FOUND"));
    }

    @Test
    void rejectsATraversingPath() {
        assertThatThrownBy(() -> GitRepositoryService.readComposeFile(repo, "../outside/compose.yaml", null))
                .isInstanceOfSatisfying(GitOperationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_COMPOSE_PATH"));
    }

    @Test
    void rejectsAFileOverTheSizeCap() throws IOException {
        Files.writeString(repo.resolve("deploy/big.yaml"), "x".repeat(500 * 1024 + 1));
        assertThatThrownBy(() -> GitRepositoryService.readComposeFile(repo, "deploy/big.yaml", null))
                .isInstanceOfSatisfying(GitOperationException.class, e -> assertThat(e.code()).isEqualTo("COMPOSE_FILE_TOO_LARGE"));
    }
}
