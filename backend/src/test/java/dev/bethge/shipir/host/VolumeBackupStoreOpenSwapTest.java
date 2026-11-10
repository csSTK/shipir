package dev.bethge.shipir.host;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VolumeBackupStore#open} walks host/volume/file through no-follow handles: a directory or file swapped for a
 * link between the check and the open is never followed. The seam runs the swap exactly in that gap.
 */
class VolumeBackupStoreOpenSwapTest {

    private static final String NAME = "data-20260101T000000Z.tar";
    private static final String SECRET = "TOP-SECRET-OUTSIDE";

    @TempDir
    Path tmp;

    private Path base;
    private Path outside;
    private VolumeBackupStore store;
    private final UUID host = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        base = tmp.resolve("store");
        outside = tmp.resolve("outside");
        Path volumeDir = Files.createDirectories(base.resolve(host.toString()).resolve("data"));
        Files.writeString(volumeDir.resolve(NAME), "inside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve(NAME), SECRET);
        store = new VolumeBackupStore(new VolumeBackupProperties(DataSize.ofMegabytes(1), "alpine:3", base, false,
                Duration.ofMinutes(1)), Clock.systemUTC());
    }

    private final AtomicBoolean fired = new AtomicBoolean();

    private void at(String stage, Runnable action) {
        store.seam = s -> {
            if (s.equals(stage) && fired.compareAndSet(false, true)) {
                action.run();
            }
        };
    }

    private Path volumeDir() {
        return base.resolve(host.toString()).resolve("data");
    }

    private void volumeDirToLink() {
        try {
            Files.move(volumeDir(), volumeDir().resolveSibling("data.orig"));
            Files.createSymbolicLink(volumeDir(), outside);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String contentOf(Optional<InputStream> in) throws IOException {
        if (in.isEmpty()) {
            return "";
        }
        try (InputStream stream = in.get()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String openOrRefuse() throws IOException {
        try {
            return contentOf(store.open(host, "data", NAME));
        } catch (IOException | RuntimeException refused) {
            return "";
        }
    }

    @Test
    void opensTheStoredArchive() throws IOException {
        assertThat(contentOf(store.open(host, "data", NAME))).isEqualTo("inside");
    }

    @Test
    void neverServesAVolumeDirectorySwappedForALinkBeforeItIsOpened() throws IOException {
        at("open:data", this::volumeDirToLink);
        assertThat(openOrRefuse()).doesNotContain(SECRET);
        assertThat(fired).as("the race window (seam stage) was reached").isTrue();
    }

    @Test
    void neverServesALeafSwappedForALink() throws IOException {
        at("leaf:" + NAME, () -> {
            try {
                Files.delete(volumeDir().resolve(NAME));
                Files.createSymbolicLink(volumeDir().resolve(NAME), outside.resolve(NAME));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(openOrRefuse()).doesNotContain(SECRET);
        assertThat(fired).as("the race window (seam stage) was reached").isTrue();
    }

    @Test
    void readsTheOpenedDirectoryWhenItIsSwappedAfterwards() throws IOException {
        at("leaf:" + NAME, this::volumeDirToLink);
        assertThat(contentOf(store.open(host, "data", NAME))).isEqualTo("inside");
        assertThat(fired).as("the race window (seam stage) was reached").isTrue();
    }

    @Test
    void aMissingStoreDirectoryIsEmpty() throws IOException {
        VolumeBackupStore empty = new VolumeBackupStore(new VolumeBackupProperties(DataSize.ofMegabytes(1), "alpine:3",
                tmp.resolve("missing"), false, Duration.ofMinutes(1)), Clock.systemUTC());
        assertThat(empty.open(host, "data", NAME)).isEmpty();
    }
}
