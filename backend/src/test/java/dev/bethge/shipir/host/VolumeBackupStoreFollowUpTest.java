package dev.bethge.shipir.host;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Edge cases of {@link VolumeBackupStore}: non-directory store path, unreadable entries, vanished directories, swaps. */
class VolumeBackupStoreFollowUpTest {

    @TempDir
    Path base;
    @TempDir
    Path outside;

    private final UUID host = UUID.randomUUID();

    private VolumeBackupStore storeAt(Path dir) {
        return new VolumeBackupStore(new VolumeBackupProperties(DataSize.ofMegabytes(1), "alpine:3", dir, false, Duration.ofMinutes(1)),
                Clock.systemUTC());
    }

    private Path put(String volume, String name) throws Exception {
        Path dir = Files.createDirectories(base.resolve(host.toString()).resolve(volume));
        return Files.writeString(dir.resolve(name), "x");
    }

    @Test
    void pruneStalePartialsReturnsZeroWhenTheStorePathIsARegularFile() throws Exception {
        Path file = Files.writeString(outside.resolve("not-a-dir"), "x");

        assertThat(storeAt(file).pruneStalePartials(Duration.ZERO)).isZero();
    }

    @Test
    void listSkipsEntriesWhoseAttributesCannotBeReadInsteadOfFailingTheListing() throws Exception {
        put("data", "data-20260101T000000Z.tar");
        Path volume = base.resolve(host.toString()).resolve("data");
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "permissions are ignored for root");
        VolumeBackupStore store = storeAt(base);
        // listing needs read, reading an entry's attributes needs search permission: drop only the latter
        store.seam = stage -> {
            if (stage.equals("opened:data")) {
                try {
                    Files.setPosixFilePermissions(volume, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        try {
            assertThat(store.list(host, "data")).isEmpty();
        } finally {
            Files.setPosixFilePermissions(volume, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void writeSucceedsWhenTheVolumeDirectoryDisappearsBeforeTheRetentionPass() throws Exception {
        VolumeBackupStore store = storeAt(base);
        AtomicInteger opens = new AtomicInteger();
        store.seam = stage -> {
            if (stage.equals("open:data") && opens.incrementAndGet() == 2) {
                try {
                    Files.move(base.resolve(host.toString()).resolve("data"), base.resolve(host.toString()).resolve("data.orig"));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        Path written = store.write(host, "data", new ByteArrayInputStream("x".getBytes()), Instant.parse("2026-01-01T00:00:00Z"), 3);

        assertThat(written.getFileName().toString()).isEqualTo("data-20260101T000000Z.tar");
        assertThat(base.resolve(host.toString()).resolve("data.orig").resolve(written.getFileName())).exists();
    }

    @Test
    void deleteAllNeverFollowsAVolumeDirectorySwappedForALinkBeforeItIsRemoved() throws Exception {
        put("data", "data-20260101T000000Z.tar");
        Path victim = Files.writeString(outside.resolve("victim.txt"), "keep");
        VolumeBackupStore store = storeAt(base);
        store.seam = stage -> {
            if (stage.equals("deletedir:data")) {
                try {
                    Path vol = base.resolve(host.toString()).resolve("data");
                    Files.delete(vol);
                    Files.createSymbolicLink(vol, outside);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        try {
            store.deleteAll(host, "data");
        } catch (HostException expectedFailure) {
            // refusing is fine; following is not
        }

        assertThat(victim).hasContent("keep");
        assertThat(outside).exists();
        assertThat(Files.isSymbolicLink(base.resolve(host.toString()).resolve("data"))).isTrue();
    }
}
