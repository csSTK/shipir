package dev.bethge.shipir.host;

import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.host.dto.StoredVolumeBackup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link VolumeBackupStore#list}, {@code write}, {@code deleteAll} and {@code pruneStalePartials} use held no-follow
 * directory handles: a directory swapped for a link between the check and the use is never followed. The seam runs the
 * swap exactly in that gap; every test asserts that the gap was reached.
 */
class VolumeBackupStoreSwapTest {

    private static final String NAME = "data-20260101T000000Z.tar";
    private static final String PART = ".backup-1.part";
    private static final String SECRET = "TOP-SECRET-OUTSIDE";

    @TempDir
    Path tmp;

    private Path base;
    private Path outside;
    private VolumeBackupStore store;
    private final UUID host = UUID.randomUUID();
    private final AtomicBoolean fired = new AtomicBoolean();

    @BeforeEach
    void setUp() throws IOException {
        base = tmp.resolve("store");
        outside = tmp.resolve("outside");
        Files.createDirectories(volumeDir());
        Files.writeString(volumeDir().resolve(NAME), "inside");
        Files.writeString(volumeDir().resolve(PART), "partial");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve(NAME), SECRET);
        Files.writeString(outside.resolve(PART), SECRET);
        store = new VolumeBackupStore(new VolumeBackupProperties(DataSize.ofMegabytes(1), "alpine:3", base, false,
                Duration.ofMinutes(1)), Clock.systemUTC());
    }

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

    private void assertOutsideUntouched() throws IOException {
        assertThat(outside.resolve(NAME)).hasContent(SECRET);
        assertThat(outside.resolve(PART)).hasContent(SECRET);
        try (Stream<Path> s = Files.list(outside)) {
            assertThat(s.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder(NAME, PART);
        }
    }

    private static ByteArrayInputStream content() {
        return new ByteArrayInputStream("new".getBytes());
    }

    // ---- list ----

    @Test
    void listNeverListsAVolumeDirectorySwappedForALinkBeforeItIsOpened() throws IOException {
        at("open:data", this::volumeDirToLink);
        List<StoredVolumeBackup> result = store.list(host, "data");
        assertThat(fired).as("the race window (seam stage) was reached").isTrue();
        assertThat(result).isEmpty();
        assertOutsideUntouched();
    }

    @Test
    void listReadsTheOpenedDirectoryWhenItIsSwappedAfterwards() {
        store.seam = s -> {
            if (s.equals("opened:data") && fired.compareAndSet(false, true)) {
                volumeDirToLink();
            }
        };
        assertThat(store.list(host, "data")).extracting(StoredVolumeBackup::name).containsExactly(NAME);
        assertThat(fired).as("the race window (seam stage) was reached").isTrue();
    }

    // ---- write ----

    @Test
    void writeRefusesAVolumeDirectorySwappedForALinkBeforeItIsOpened() throws IOException {
        at("open:data", this::volumeDirToLink);
        assertThatThrownBy(() -> store.write(host, "data", content(), Instant.parse("2026-02-01T00:00:00Z"), 3))
                .isInstanceOf(PathValidationException.class);
        assertThat(fired).as("the race window (seam stage) was reached").isTrue();
        assertOutsideUntouched();
    }

    @Test
    void writeStaysInTheOpenedDirectoryWhenItIsSwappedForALinkBeforeTheMove() throws IOException {
        String target = "data-20260201T000000Z.tar";
        at("move:" + target, this::volumeDirToLink);
        store.write(host, "data", content(), Instant.parse("2026-02-01T00:00:00Z"), 3);
        assertThat(fired).as("the race window (seam stage) was reached").isTrue();
        assertOutsideUntouched();
        assertThat(volumeDir().resolveSibling("data.orig").resolve(target)).hasContent("new");
    }

    @Test
    void writeRetentionStaysInTheOpenedDirectoryWhenItIsSwappedForALink() throws IOException {
        at("delete:" + NAME, this::volumeDirToLink);
        store.write(host, "data", content(), Instant.parse("2026-02-01T00:00:00Z"), 1);
        assertThat(fired).as("the race window (seam stage) was reached").isTrue();
        assertOutsideUntouched();
    }

    // ---- deleteAll ----

    @Test
    void deleteAllRefusesAVolumeDirectorySwappedForALinkBeforeItIsOpened() throws IOException {
        at("opensub:data", this::volumeDirToLink);
        assertThat(store.deleteAll(host, "data")).isZero();
        assertThat(fired).as("the race window (seam stage) was reached").isTrue();
        assertOutsideUntouched();
    }

    @Test
    void deleteAllStaysInTheOpenedDirectoryWhenItIsSwappedForALinkMidWay() throws IOException {
        at("delete:" + NAME, this::volumeDirToLink);
        try {
            store.deleteAll(host, "data");
        } catch (HostException refused) {
            // acceptable: refusing is fine, following is not
        }
        assertThat(fired).as("the race window (seam stage) was reached").isTrue();
        assertOutsideUntouched();
    }

    // ---- pruneStalePartials ----

    @Test
    void pruneSkipsAVolumeDirectorySwappedForALinkBeforeItIsOpened() throws IOException {
        at("opensub:data", this::volumeDirToLink);
        assertThat(store.pruneStalePartials(Duration.ZERO)).isZero();
        assertThat(fired).as("the race window (seam stage) was reached").isTrue();
        assertOutsideUntouched();
    }

    @Test
    void pruneStaysInTheOpenedDirectoryWhenItIsSwappedForALinkBeforeTheDelete() throws IOException {
        at("delete:" + PART, this::volumeDirToLink);
        store.pruneStalePartials(Duration.ZERO);
        assertThat(fired).as("the race window (seam stage) was reached").isTrue();
        assertOutsideUntouched();
    }
}
