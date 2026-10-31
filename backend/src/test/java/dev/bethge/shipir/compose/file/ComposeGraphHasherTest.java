package dev.bethge.shipir.compose.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ComposeGraphHasherTest {

    @TempDir
    Path dir;

    private String hash() {
        return ComposeGraphHasher.hash(dir).orElseThrow();
    }

    @Test
    void sameTreeSameHash() throws Exception {
        Files.writeString(dir.resolve("compose.yaml"), "a");
        String first = hash();
        assertThat(hash()).isEqualTo(first).matches("[0-9a-f]{64}");
    }

    @Test
    void changedContentNewFileAndRenameChangeTheHash() throws Exception {
        Files.writeString(dir.resolve("compose.yaml"), "a");
        String base = hash();
        Files.writeString(dir.resolve("compose.yaml"), "b");
        String edited = hash();
        assertThat(edited).isNotEqualTo(base);
        Files.writeString(dir.resolve("extra.yaml"), "x");
        String added = hash();
        assertThat(added).isNotEqualTo(edited);
        Files.move(dir.resolve("extra.yaml"), dir.resolve("other.yaml"));
        assertThat(hash()).isNotEqualTo(added);
    }

    @Test
    void contentCannotBeShiftedBetweenFileNames() throws Exception {
        Files.writeString(dir.resolve("a"), "bc");
        String one = hash();
        Files.delete(dir.resolve("a"));
        Files.writeString(dir.resolve("ab"), "c");
        assertThat(hash()).isNotEqualTo(one);
    }

    @Test
    void nestedFilesAreCovered() throws Exception {
        Files.createDirectories(dir.resolve("repo/conf"));
        Files.writeString(dir.resolve("repo/conf/a.env"), "A=1");
        String base = hash();
        Files.writeString(dir.resolve("repo/conf/a.env"), "A=2");
        assertThat(hash()).isNotEqualTo(base);
    }

    @Test
    void gitMetadataOfShipirsOwnCheckoutIsIgnored() throws Exception {
        Files.createDirectories(dir.resolve("repo/.git"));
        Files.writeString(dir.resolve("repo/.git/HEAD"), "ref");
        String base = hash();
        Files.writeString(dir.resolve("repo/.git/HEAD"), "other ref");
        Files.writeString(dir.resolve("repo/.git/new"), "x");
        assertThat(hash()).isEqualTo(base);
    }

    @Test
    void gitDirectoriesAnOperatorCanWriteAreHashed() throws Exception {
        Files.writeString(dir.resolve("compose.yaml"), "a");
        String base = hash();
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/f.yml"), "x");
        String top = hash();
        assertThat(top).isNotEqualTo(base);
        Files.createDirectories(dir.resolve("x/.git"));
        Files.writeString(dir.resolve("x/.git/f.yml"), "x");
        String nested = hash();
        assertThat(nested).isNotEqualTo(top);
        Files.createDirectories(dir.resolve("repo/sub/.git"));
        Files.writeString(dir.resolve("repo/sub/.git/f.yml"), "x");
        assertThat(hash()).isNotEqualTo(nested);
    }

    @Test
    void rollbackOverrideAtTheTopIsIgnoredButNotElsewhere() throws Exception {
        String base = hash();
        Files.writeString(dir.resolve("compose.rollback.yaml"), "x");
        assertThat(hash()).isEqualTo(base);
        Files.createDirectories(dir.resolve("sub"));
        String withSub = hash();
        Files.writeString(dir.resolve("sub/compose.rollback.yaml"), "x");
        assertThat(hash()).isNotEqualTo(withSub);
    }

    @Test
    void directoryOverTheSizeCapFailsClosed() throws Exception {
        Files.writeString(dir.resolve("a"), "12345");
        Files.writeString(dir.resolve("b"), "12345");
        assertThat(ComposeGraphHasher.hash(dir, 10)).isPresent();
        assertThat(ComposeGraphHasher.hash(dir, 9)).isEmpty();
    }

    @Test
    void bigFileIsStreamedWithoutBeingLoaded() throws Exception {
        try (var out = Files.newOutputStream(dir.resolve("big"))) {
            byte[] chunk = new byte[1 << 20];
            for (int i = 0; i < 20; i++) {
                out.write(chunk);
            }
        }
        assertThat(ComposeGraphHasher.hash(dir)).isPresent();
    }

    @Test
    void symlinkInsideTheDirectoryIsFine() throws Exception {
        Files.writeString(dir.resolve("real.env"), "A=1");
        Files.createSymbolicLink(dir.resolve("link.env"), dir.resolve("real.env"));
        String base = hash();
        Files.writeString(dir.resolve("real.env"), "A=2");
        assertThat(hash()).isNotEqualTo(base);
    }

    @Test
    void symlinkLeavingTheDirectoryFailsClosed() throws Exception {
        Path outside = Files.createTempFile("outside", ".txt");
        try {
            Files.createSymbolicLink(dir.resolve("link"), outside);
            assertThat(ComposeGraphHasher.hash(dir)).isEmpty();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void symlinkWithDotDotEscapeAndDanglingLinkFailClosed() throws Exception {
        Files.createSymbolicLink(dir.resolve("up"), Path.of("../"));
        assertThat(ComposeGraphHasher.hash(dir)).isEmpty();
        Files.delete(dir.resolve("up"));
        Files.createSymbolicLink(dir.resolve("dangling"), dir.resolve("missing"));
        assertThat(ComposeGraphHasher.hash(dir)).isEmpty();
    }

    @Test
    void missingDirectoryHashesLikeAnEmptyOne() throws Exception {
        assertThat(ComposeGraphHasher.hash(dir.resolve("nope"))).isEqualTo(ComposeGraphHasher.hash(dir));
    }

    @Test
    void unreadableDirectoryFailsClosed() throws Exception {
        Path file = Files.writeString(dir.resolve("plain"), "x");
        assertThat(ComposeGraphHasher.hash(file)).isEmpty(); // not a directory
    }

    @Test
    void shipirsOwnBackupDirectoryAtTheTopIsIgnoredButNotElsewhere() throws Exception {
        Files.writeString(dir.resolve("compose.yaml"), "a");
        String base = hash();
        Files.createDirectories(dir.resolve(".shipir-backups/abc"));
        Files.writeString(dir.resolve(".shipir-backups/abc/1.bak"), "old");
        assertThat(hash()).isEqualTo(base);
        Files.writeString(dir.resolve(".shipir-backups/abc/2.bak"), "newer");
        assertThat(hash()).isEqualTo(base);
        Files.createDirectories(dir.resolve("sub/.shipir-backups"));
        Files.writeString(dir.resolve("sub/.shipir-backups/x"), "y");
        assertThat(hash()).isNotEqualTo(base);
    }

    @Test
    void symlinkIntoShipirsOwnUnhashedPathsFailsClosed() throws Exception {
        Files.createDirectories(dir.resolve("repo/.git"));
        Files.writeString(dir.resolve("repo/.git/config"), "x");
        Files.createSymbolicLink(dir.resolve("gitlink"), Path.of("repo/.git/config"));
        assertThat(ComposeGraphHasher.hash(dir)).isEmpty();
        Files.delete(dir.resolve("gitlink"));
        Files.createSymbolicLink(dir.resolve("gitdir"), Path.of("repo/.git"));
        assertThat(ComposeGraphHasher.hash(dir)).isEmpty();
        Files.delete(dir.resolve("gitdir"));
        Files.createDirectories(dir.resolve(".shipir-backups/abc"));
        Files.writeString(dir.resolve(".shipir-backups/abc/1.bak"), "old");
        Files.createSymbolicLink(dir.resolve("bak"), Path.of(".shipir-backups/abc/1.bak"));
        assertThat(ComposeGraphHasher.hash(dir)).isEmpty();
    }

    @Test
    void symlinkToANormalRepoFileIsStillFine() throws Exception {
        Files.createDirectories(dir.resolve("repo"));
        Files.writeString(dir.resolve("repo/app.env"), "A=1");
        Files.createSymbolicLink(dir.resolve("link"), Path.of("repo/app.env"));
        assertThat(ComposeGraphHasher.hash(dir)).isPresent();
    }

    @Test
    void directoryWithTooManyEntriesFailsClosed() throws Exception {
        for (int i = 0; i < 5; i++) {
            Files.createFile(dir.resolve("f" + i));
        }
        Files.createDirectories(dir.resolve("d"));
        assertThat(ComposeGraphHasher.hash(dir, ComposeGraphHasher.MAX_BYTES, 6)).isPresent();
        assertThat(ComposeGraphHasher.hash(dir, ComposeGraphHasher.MAX_BYTES, 5)).isEmpty();
    }

    @Test
    void entryCapIsNotAboveWhatTheConfigTreeLists() {
        assertThat(ComposeGraphHasher.MAX_ENTRIES).isEqualTo(5000);
    }
}
