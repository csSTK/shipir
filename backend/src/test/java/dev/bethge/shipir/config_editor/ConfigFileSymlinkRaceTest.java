package dev.bethge.shipir.config_editor;

import dev.bethge.shipir.compose.DeploymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Stress test: a directory flipped between real dir and symlink while the editor reads/writes must never leak or touch outside content. */
class ConfigFileSymlinkRaceTest {

    private static final String SECRET = "TOP-SECRET-OUTSIDE";

    @TempDir
    Path tmp;

    @Test
    void flippingDirectoryNeverLeaksOrModifiesOutsideContent() throws Exception {
        Path root = tmp.resolve("deployment");
        Path outside = tmp.resolve("outside");
        Files.createDirectories(root.resolve("conf"));
        Files.createDirectories(outside);
        Files.writeString(root.resolve("conf/app.txt"), "inside");
        Files.writeString(outside.resolve("app.txt"), SECRET);
        UUID host = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        DeploymentService deployments = mock(DeploymentService.class);
        when(deployments.directoryOf(host, id)).thenReturn(root);
        when(deployments.whileNoRunInProgress(any(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        ConfigFileService service = new ConfigFileService(deployments, new ConfigFileProperties(1024, 5), Clock.systemUTC());

        AtomicBoolean stop = new AtomicBoolean();
        Path real = root.resolve("conf.real");
        Thread flipper = new Thread(() -> {
            Path conf = root.resolve("conf");
            while (!stop.get()) {
                try {
                    Files.move(conf, real);
                    Files.createSymbolicLink(conf, outside);
                    Files.delete(conf);
                    Files.move(real, conf);
                } catch (IOException ignored) {
                    // keep flipping
                }
            }
        });
        flipper.start();
        try {
            long end = System.nanoTime() + 3_000_000_000L;
            while (System.nanoTime() < end) {
                try {
                    assertThat(service.read(host, id, "conf/app.txt").content()).isNotEqualTo(SECRET);
                } catch (RuntimeException expectedWhileFlipping) {
                    // refusals and missing files are fine
                }
                try {
                    service.write(host, id, "conf/app.txt", "x");
                } catch (RuntimeException expectedWhileFlipping) {
                    // fine
                }
            }
        } finally {
            stop.set(true);
            flipper.join();
        }
        assertThat(Files.readString(outside.resolve("app.txt"))).isEqualTo(SECRET);
        try (var s = Files.list(outside)) {
            assertThat(s.count()).isEqualTo(1);
        }
    }
}
