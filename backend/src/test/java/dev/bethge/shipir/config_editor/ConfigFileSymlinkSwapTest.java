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
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** Runs {@code action} once, when the service reaches {@code stage} (a point inside the handle walk). */
    private void at(String stage, Runnable action) {
        AtomicBoolean fired = new AtomicBoolean();
        service.seam = s -> {
            if (s.equals(stage) && fired.compareAndSet(false, true)) {
                action.run();
            }
        };
    }

    private void dirToLink() {
        try {
            Files.move(root.resolve("conf"), root.resolve("conf.orig"));
            Files.createSymbolicLink(root.resolve("conf"), outside);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void leafToLink() {
        try {
            Files.delete(root.resolve("conf/app.txt"));
            Files.createSymbolicLink(root.resolve("conf/app.txt"), outside.resolve("app.txt"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertOutsideUntouched() throws IOException {
        assertThat(Files.readString(outside.resolve("app.txt"))).isEqualTo(SECRET);
        try (Stream<Path> s = Files.list(outside)) {
            assertThat(s.map(p -> p.getFileName().toString())).containsExactly("app.txt");
        }
    }

    private void assertNoBackupHoldsSecret() throws IOException {
        if (!Files.exists(root.resolve(".shipir-backups"))) {
            return;
        }
        try (Stream<Path> s = Files.walk(root.resolve(".shipir-backups"))) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(p)).doesNotContain(SECRET);
            }
        }
    }

    @Test
    void readRefusesDirectorySwappedBeforeItIsOpened() {
        at("open:conf", this::dirToLink);
        assertThatThrownBy(() -> service.read(host, id, "conf/app.txt")).isInstanceOf(ConfigFileException.class);
    }

    @Test
    void readNeverLeaksWhenDirectoryIsSwappedAfterItIsOpened() {
        at("opened:conf", this::dirToLink);
        assertThat(service.read(host, id, "conf/app.txt").content()).isEqualTo("inside");
    }

    @Test
    void readRefusesLeafSwappedForLink() {
        at("leaf:app.txt", this::leafToLink);
        assertThatThrownBy(() -> service.read(host, id, "conf/app.txt")).isInstanceOf(ConfigFileException.class);
    }

    @Test
    void writeRefusesDirectorySwappedBeforeItIsOpened() throws IOException {
        at("open:conf", this::dirToLink);
        assertThatThrownBy(() -> service.write(host, id, "conf/app.txt", "pwned")).isInstanceOf(ConfigFileException.class);
        assertOutsideUntouched();
    }

    @Test
    void writeStaysInTheOpenedDirectoryWhenItIsSwappedAfterwards() throws IOException {
        at("opened:conf", this::dirToLink);
        service.write(host, id, "conf/app.txt", "v2");
        assertOutsideUntouched();
        assertThat(Files.readString(root.resolve("conf.orig/app.txt"))).isEqualTo("v2");
    }

    @Test
    void writeStaysInTheOpenedDirectoryWhenItIsSwappedBeforeTheMove() throws IOException {
        at("move:app.txt", this::dirToLink);
        service.write(host, id, "conf/app.txt", "v2");
        assertOutsideUntouched();
        assertThat(Files.readString(root.resolve("conf.orig/app.txt"))).isEqualTo("v2");
    }

    @Test
    void writeRefusesLeafSwappedForLinkAndLeaksNothingIntoBackups() throws IOException {
        at("leaf:app.txt", this::leafToLink);
        assertThatThrownBy(() -> service.write(host, id, "conf/app.txt", "pwned")).isInstanceOf(ConfigFileException.class);
        assertOutsideUntouched();
        assertNoBackupHoldsSecret();
    }

    @Test
    void deleteRefusesDirectorySwappedBeforeItIsOpened() throws IOException {
        at("open:conf", this::dirToLink);
        assertThatThrownBy(() -> service.delete(host, id, "conf/app.txt")).isInstanceOf(ConfigFileException.class);
        assertOutsideUntouched();
    }

    @Test
    void deleteOnlyTouchesTheOpenedDirectoryWhenItIsSwappedBeforeTheUnlink() throws IOException {
        at("delete:app.txt", this::dirToLink);
        service.delete(host, id, "conf/app.txt");
        assertOutsideUntouched();
        assertThat(root.resolve("conf.orig/app.txt")).doesNotExist();
    }

    @Test
    void deleteRefusesLeafSwappedForLink() throws IOException {
        at("leaf:app.txt", this::leafToLink);
        assertThatThrownBy(() -> service.delete(host, id, "conf/app.txt")).isInstanceOf(ConfigFileException.class);
        assertOutsideUntouched();
        assertNoBackupHoldsSecret();
    }

    @Test
    void restoreRefusesDirectorySwappedBeforeItIsOpened() throws IOException {
        service.write(host, id, "conf/app.txt", "v2");
        String backupId = service.backups(host, id, "conf/app.txt").get(0).id();
        at("open:conf", this::dirToLink);
        assertThatThrownBy(() -> service.restore(host, id, "conf/app.txt", backupId)).isInstanceOf(ConfigFileException.class);
        assertOutsideUntouched();
    }

    @Test
    void restoreStaysInTheOpenedDirectoryWhenItIsSwappedBeforeTheMove() throws IOException {
        service.write(host, id, "conf/app.txt", "v2");
        String backupId = service.backups(host, id, "conf/app.txt").get(0).id();
        at("move:app.txt", this::dirToLink);
        service.restore(host, id, "conf/app.txt", backupId);
        assertOutsideUntouched();
        assertThat(Files.readString(root.resolve("conf.orig/app.txt"))).isEqualTo("inside");
    }

    @Test
    void restoreRefusesBackupSwappedForLink() throws IOException {
        service.write(host, id, "conf/app.txt", "v2");
        String backupId = service.backups(host, id, "conf/app.txt").get(0).id();
        Path backup;
        try (Stream<Path> s = Files.list(root.resolve(".shipir-backups"))) {
            backup = s.findFirst().orElseThrow().resolve(backupId + ".bak");
        }
        at("leaf:" + backupId + ".bak", () -> {
            try {
                Files.delete(backup);
                Files.createSymbolicLink(backup, outside.resolve("app.txt"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThatThrownBy(() -> service.restore(host, id, "conf/app.txt", backupId)).isInstanceOf(ConfigFileException.class);
        assertThat(Files.readString(root.resolve("conf/app.txt"))).isEqualTo("v2");
        assertOutsideUntouched();
    }

    @Test
    void writeIntoMissingDirectoriesCreatesThemAndWorks() throws IOException {
        service.write(host, id, "new/deep/file.txt", "hello");
        assertThat(Files.readString(root.resolve("new/deep/file.txt"))).isEqualTo("hello");
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
