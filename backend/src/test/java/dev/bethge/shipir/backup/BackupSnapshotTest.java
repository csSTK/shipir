package dev.bethge.shipir.backup;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** The export reads all tables from one snapshot: commits that happen while it runs do not show up in it. */
class BackupSnapshotTest extends BackupTestBase {

    @Autowired BackupService service;
    @Autowired DataSource dataSource;

    @Test
    void commitBetweenTwoTableExportsIsNotPartOfTheArchive() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<Void> export;
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement st = other.createStatement()) {
                // Holds the export at docker_hosts, after it has listed the tables and read the earlier ones.
                st.execute("LOCK TABLE docker_hosts IN ACCESS EXCLUSIVE MODE");
                st.executeUpdate("UPDATE docker_hosts SET name = 'Changed concurrently'");
                export = CompletableFuture.runAsync(() -> {
                    try {
                        service.write(out, PASSPHRASE.toCharArray());
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
                long deadline = System.currentTimeMillis() + 30_000;
                while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class) == 0) {
                    assertThat(System.currentTimeMillis()).isLessThan(deadline);
                    Thread.sleep(50);
                }
                other.commit();
            }
        }
        export.get();

        Map<String, byte[]> files = decryptAndUntar(out.toByteArray(), PASSPHRASE);
        String hosts = new String(files.get("db/docker_hosts.jsonl"), StandardCharsets.UTF_8);
        assertThat(hosts).contains("Backup host").doesNotContain("Changed concurrently");
    }
}
