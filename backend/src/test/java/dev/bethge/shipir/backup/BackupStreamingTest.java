package dev.bethge.shipir.backup;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A large stacks file is read and written in small chunks, never held in memory. */
class BackupStreamingTest extends BackupTestBase {

    @Autowired BackupService service;

    @Test
    void largeFileIsStreamedInSmallChunks() throws Exception {
        Path big = Files.createDirectories(BackupTestBase.STACKS.resolve("h3/big")).resolve("data.bin");
        try (RandomAccessFile f = new RandomAccessFile(big.toFile(), "rw")) {
            f.setLength(512L * 1024 * 1024); // sparse, 512 MiB
        }
        Counting out = new Counting();
        try {
            service.write(out, PASSPHRASE.toCharArray());
        } finally {
            Files.deleteIfExists(big);
        }
        assertThat(out.maxWrite).isLessThanOrEqualTo(256 * 1024);
        assertThat(out.total).isGreaterThan(0).isLessThan(16L * 1024 * 1024); // zeros compress
    }

    @Test
    void failedStreamIsAuditedAsFailure() {
        jdbc.update("DELETE FROM audit_log WHERE action = 'BACKUP_EXPORT_RESULT'");
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("client gone");
            }
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.export(PASSPHRASE.toCharArray()).writeTo(broken)).isInstanceOf(IOException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'BACKUP_EXPORT_RESULT' "
                + "AND outcome = 'FAILURE'", Integer.class)).isEqualTo(1);
    }

    static final class Counting extends OutputStream {
        long total;
        int maxWrite;

        @Override
        public void write(int b) {
            total++;
            maxWrite = Math.max(maxWrite, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            total += len;
            maxWrite = Math.max(maxWrite, len);
        }
    }
}
