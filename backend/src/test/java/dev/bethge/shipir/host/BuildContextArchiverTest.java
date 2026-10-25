package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rules for packing a cloned repository into a build context. */
class BuildContextArchiverTest {

    @TempDir
    Path tmp;

    private Path repo() throws IOException {
        return Files.createDirectories(tmp.resolve("repo"));
    }

    private List<String> archive(Path repo, long max) throws IOException {
        Path target = tmp.resolve("ctx.tar");
        BuildContextArchiver.archive(repo, target, max);
        List<String> names = new ArrayList<>();
        try (TarArchiveInputStream in = new TarArchiveInputStream(Files.newInputStream(target))) {
            for (TarArchiveEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                assertThat(e.isFile() || e.isDirectory()).as("only files and directories: " + e.getName()).isTrue();
                names.add(e.getName());
            }
        }
        return names;
    }

    @Test
    void contentOverTheLimitIsRefused() throws Exception {
        Path repo = repo();
        Files.write(repo.resolve("a.bin"), new byte[600]);
        Files.write(repo.resolve("b.bin"), new byte[600]);
        assertThatThrownBy(() -> archive(repo, 1000)).isInstanceOfSatisfying(DockerConnectionException.class,
                e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void contentWithinTheLimitIsArchived() throws Exception {
        Path repo = repo();
        Files.write(repo.resolve("a.bin"), new byte[600]);
        assertThat(archive(repo, 1000)).containsExactly("a.bin");
    }

    @Test
    void symlinkedFilesAndDirectoriesAreNotArchived() throws Exception {
        Path repo = repo();
        Files.writeString(repo.resolve("Dockerfile"), "FROM busybox\n");
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(repo.resolve("linkdir"), outside);
        Files.createSymbolicLink(repo.resolve("linkfile"), outside.resolve("secret.txt"));
        assertThat(archive(repo, 10_000)).containsExactly("Dockerfile");
    }

    @Test
    void gitMetadataIsSkippedAtTheTopAndNested() throws Exception {
        Path repo = repo();
        Files.writeString(repo.resolve("Dockerfile"), "FROM busybox\n");
        Files.createDirectories(repo.resolve(".git/objects"));
        Files.writeString(repo.resolve(".git/config"), "[core]");
        Files.createDirectories(repo.resolve("vendor/lib/.git"));
        Files.writeString(repo.resolve("vendor/lib/.git/config"), "[core]");
        Files.writeString(repo.resolve("vendor/lib/code.txt"), "x");
        assertThat(archive(repo, 10_000)).containsExactlyInAnyOrder("Dockerfile", "vendor/", "vendor/lib/",
                "vendor/lib/code.txt");
    }

    @Test
    void namesAreRelativeWithoutDotDot() throws Exception {
        Path repo = repo();
        Files.createDirectories(repo.resolve("src/deep"));
        Files.writeString(repo.resolve("src/deep/app.sh"), "echo");
        Files.writeString(repo.resolve("Dockerfile"), "FROM busybox\n");
        assertThat(archive(repo, 10_000)).isNotEmpty().allSatisfy(n -> {
            assertThat(n).doesNotStartWith("/");
            assertThat(n.split("/")).doesNotContain("..", ".");
        }).contains("src/deep/app.sh");
    }
}
