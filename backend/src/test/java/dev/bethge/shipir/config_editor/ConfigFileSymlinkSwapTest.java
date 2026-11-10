package dev.bethge.shipir.config_editor;

import dev.bethge.shipir.compose.DeploymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A symlink swapped in after the path check (TOCTOU) must never be followed. The service's test seam
 * ({@code beforeUse}) runs the swap exactly in the gap between the up-front check and the file operation.
 */
class ConfigFileSymlinkSwapTest {

    private static final String SECRET = "TOP-SECRET-OUTSIDE";

    @TempDir
    Path tmp;

    private Path root;
    private Path outside;
    private ConfigFileService service;
    private final UUID host = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        root = tmp.resolve("deployment");
        outside = tmp.resolve("outside");
        Files.createDirectories(root.resolve("conf"));
        Files.createDirectories(outside);
        Files.writeString(root.resolve("conf/app.txt"), "inside");
        Files.writeString(outside.resolve("app.txt"), SECRET);

        DeploymentService deployments = mock(DeploymentService.class);
        when(deployments.directoryOf(host, id)).thenReturn(root);
        when(deployments.whileNoRunInProgress(any(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        service = new ConfigFileService(deployments, new ConfigFileProperties(1024, 5), Clock.systemUTC());
    }

    private void swapDirectoryForLink() {
        service.beforeUse = () -> {
            try {
                deleteRecursively(root.resolve("conf"));
                Files.createSymbolicLink(root.resolve("conf"), outside);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private void swapFileForLink() {
        service.beforeUse = () -> {
            try {
                Files.delete(root.resolve("conf/app.txt"));
                Files.createSymbolicLink(root.resolve("conf/app.txt"), outside.resolve("app.txt"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private void assertOutsideUntouched() throws IOException {
        assertThat(Files.readString(outside.resolve("app.txt"))).isEqualTo(SECRET);
        try (Stream<Path> s = Files.list(outside)) {
            assertThat(s.map(p -> p.getFileName().toString())).containsExactly("app.txt");
        }
    }

    @Test
    void readRefusesFileSwappedForLink() {
        swapFileForLink();
        assertThatThrownBy(() -> service.read(host, id, "conf/app.txt")).isInstanceOf(ConfigFileException.class);
    }

    @Test
    void readRefusesDirectorySwappedForLink() {
        swapDirectoryForLink();
        assertThatThrownBy(() -> service.read(host, id, "conf/app.txt")).isInstanceOf(ConfigFileException.class);
    }

    @Test
    void writeRefusesDirectorySwappedForLink() throws IOException {
        swapDirectoryForLink();
        assertThatThrownBy(() -> service.write(host, id, "conf/app.txt", "pwned")).isInstanceOf(ConfigFileException.class);
        assertOutsideUntouched();
    }

    @Test
    void writeRefusesFileSwappedForLinkAndDoesNotBackUpTarget() throws IOException {
        swapFileForLink();
        assertThatThrownBy(() -> service.write(host, id, "conf/app.txt", "pwned")).isInstanceOf(ConfigFileException.class);
        assertOutsideUntouched();
        assertThat(root.resolve(".shipir-backups")).doesNotExist();
    }

    @Test
    void deleteRefusesDirectorySwappedForLink() throws IOException {
        swapDirectoryForLink();
        assertThatThrownBy(() -> service.delete(host, id, "conf/app.txt")).isInstanceOf(ConfigFileException.class);
        assertOutsideUntouched();
    }

    @Test
    void deleteRefusesFileSwappedForLink() throws IOException {
        swapFileForLink();
        assertThatThrownBy(() -> service.delete(host, id, "conf/app.txt")).isInstanceOf(ConfigFileException.class);
        assertOutsideUntouched();
        assertThat(root.resolve(".shipir-backups")).doesNotExist();
    }

    @Test
    void restoreRefusesDirectorySwappedForLink() throws IOException {
        service.write(host, id, "conf/app.txt", "v2");
        String backupId = service.backups(host, id, "conf/app.txt").get(0).id();
        swapDirectoryForLink();
        assertThatThrownBy(() -> service.restore(host, id, "conf/app.txt", backupId)).isInstanceOf(ConfigFileException.class);
        assertOutsideUntouched();
    }

    @Test
    void restoreRefusesBackupSwappedForLink() throws IOException {
        service.write(host, id, "conf/app.txt", "v2");
        String backupId = service.backups(host, id, "conf/app.txt").get(0).id();
        Path backup = Files.list(root.resolve(".shipir-backups")).findFirst().orElseThrow().resolve(backupId + ".bak");
        service.beforeUse = () -> {
            try {
                Files.delete(backup);
                Files.createSymbolicLink(backup, outside.resolve("app.txt"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        assertThatThrownBy(() -> service.restore(host, id, "conf/app.txt", backupId)).isInstanceOf(ConfigFileException.class);
        assertThat(Files.readString(root.resolve("conf/app.txt"))).isEqualTo("v2");
        assertOutsideUntouched();
    }

    @Test
    void normalOperationsStillWork() throws IOException {
        assertThat(service.read(host, id, "conf/app.txt").content()).isEqualTo("inside");
        service.write(host, id, "conf/app.txt", "v2");
        assertThat(service.backups(host, id, "conf/app.txt")).hasSize(1);
        service.restore(host, id, "conf/app.txt", service.backups(host, id, "conf/app.txt").get(0).id());
        assertThat(Files.readString(root.resolve("conf/app.txt"))).isEqualTo("inside");
        service.delete(host, id, "conf/app.txt");
        assertThat(root.resolve("conf/app.txt")).doesNotExist();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
