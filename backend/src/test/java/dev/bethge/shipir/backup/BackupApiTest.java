package dev.bethge.shipir.backup;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BackupApiTest extends BackupTestBase {

    private static final String BODY = "{\"passphrase\":\"" + PASSPHRASE + "\"}";

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(post("/api/backup").contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void operatorAndViewerAreForbidden() throws Exception {
        for (String role : new String[]{"operator", "viewer"}) {
            Login l = login(role);
            mvc.perform(l.write(post("/api/backup")).contentType(APPLICATION_JSON).content(BODY))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void getIsNotAllowed() throws Exception {
        Login l = login("admin");
        mvc.perform(get("/api/backup").session(l.session())).andExpect(status().is4xxClientError());
    }

    @Test
    void requiresCsrfToken() throws Exception {
        Login l = login("admin");
        mvc.perform(post("/api/backup").session(l.session()).contentType(APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsWeakOrMissingPassphrase() throws Exception {
        jdbc.update("DELETE FROM audit_log WHERE action = 'BACKUP_EXPORT'");
        Login l = login("admin");
        for (String bad : new String[]{"{\"passphrase\":\"short\"}", "{\"passphrase\":\"12345678901\"}",
                "{\"passphrase\":null}", "{}", "{\"passphrase\":\"" + "x".repeat(129) + "\"}"}) {
            mvc.perform(l.write(post("/api/backup")).contentType(APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'BACKUP_EXPORT'",
                Integer.class)).isZero();
    }

    @Test
    void acceptsMinimumLengthPassphrase() throws Exception {
        Login l = login("admin");
        assertThat(download(l, "123456789012")).isNotEmpty();
    }

    @Test
    void roundTripContainsTablesAndStacksFiles() throws Exception {
        Path deployment = Files.createDirectories(BackupTestBase.STACKS.resolve("h1/web"));
        Files.writeString(deployment.resolve("compose.yaml"), "services: {}\n");
        Files.writeString(deployment.resolve(".env"), "SECRET=cipher\n");
        Path link = BackupTestBase.STACKS.resolve("h1/web/link");
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, Path.of("/etc/passwd"));
        Login l = login("admin");

        byte[] archive = download(l, PASSPHRASE);
        Map<String, byte[]> files = decryptAndUntar(archive, PASSPHRASE);

        assertThat(files).containsKeys("manifest.json", "db/users.jsonl", "db/docker_hosts.jsonl",
                "db/audit_log.jsonl", "stacks/h1/web/compose.yaml", "stacks/h1/web/.env");
        assertThat(files).doesNotContainKey("stacks/h1/web/link").doesNotContainKey("db/flyway_schema_history.jsonl");
        assertThat(new String(files.get("stacks/h1/web/compose.yaml"), StandardCharsets.UTF_8))
                .isEqualTo("services: {}\n");
        String users = new String(files.get("db/users.jsonl"), StandardCharsets.UTF_8);
        assertThat(users).contains("\"username\": \"admin\"").contains("password_hash");
        assertThat(new String(files.get("db/docker_hosts.jsonl"), StandardCharsets.UTF_8)).contains("Backup host");
        assertThat(new String(files.get("manifest.json"), StandardCharsets.UTF_8)).contains("\"format\":1");
    }

    @Test
    void wrongPassphraseCannotDecrypt() throws Exception {
        byte[] archive = download(login("admin"), PASSPHRASE);
        assertThatThrownBy(() -> decryptAndUntar(archive, "another passphrase!"))
                .isInstanceOf(SecurityException.class).hasMessage("MAC mismatch");
    }

    @Test
    void archiveHoldsNoPlaintextOfStoredData() throws Exception {
        byte[] archive = download(login("admin"), PASSPHRASE);
        assertThat(new String(archive, StandardCharsets.ISO_8859_1)).doesNotContain("Backup host", "compose.yaml");
    }

    /** The sequence documented in the {@code BackupService} Javadoc; $F = file, $1 = output dir. */
    private static final String CLI = String.join("\n",
            "set -e",
            "F=\"$0\"",
            "N=$(( $(wc -c < \"$F\") - 40 ))",
            "MACSALT=$(tail -c 40 \"$F\" | head -c 8 | od -An -tx1 | tr -d ' \\n')",
            "WANT=$(tail -c 32 \"$F\" | od -An -tx1 | tr -d ' \\n')",
            "MACKEY=$(openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -S \"$MACSALT\" -P "
                    + "-pass env:SHIPIR_BACKUP_PASSPHRASE | sed -n 's/^key=//p')",
            "GOT=$(head -c \"$N\" \"$F\" | openssl dgst -sha256 -mac HMAC -macopt hexkey:\"$MACKEY\" -r | cut -d' ' -f1)",
            "[ \"$GOT\" = \"$WANT\" ] || { echo MAC mismatch >&2; exit 1; }",
            "head -c \"$N\" \"$F\" | openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 "
                    + "-pass env:SHIPIR_BACKUP_PASSPHRASE | tar -xzf - -C \"$1\"");

    private int runCli(Path file, Path out, String passphrase) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", CLI, file.toString(), out.toString());
        pb.environment().put("SHIPIR_BACKUP_PASSPHRASE", passphrase);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return p.exitValue();
    }

    private static void assumeCli() {
        Assumptions.assumeTrue(exists("openssl") && exists("tar"), "openssl/tar not available");
        try {
            Process p = new ProcessBuilder("sh", "-c",
                    "echo a | openssl dgst -sha256 -mac HMAC -macopt hexkey:00 -r").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            Assumptions.assumeTrue(p.waitFor() == 0, "openssl lacks dgst -mac HMAC");
        } catch (Exception e) {
            Assumptions.abort("openssl not usable");
        }
    }

    @Test
    void documentedCliSequenceVerifiesAndDecryptsTheArchive() throws Exception {
        assumeCli();
        Files.writeString(Files.createDirectories(BackupTestBase.STACKS.resolve("h2/api")).resolve("compose.yaml"), "a: 1\n");
        byte[] archive = download(login("admin"), PASSPHRASE);
        Path work = Files.createTempDirectory("shipir-backup-cli");
        Path enc = work.resolve("backup.tar.gz.enc");
        Files.write(enc, archive);
        Path out = Files.createDirectory(work.resolve("restore"));

        assertThat(runCli(enc, out, PASSPHRASE)).isZero();
        assertThat(Files.readString(out.resolve("stacks/h2/api/compose.yaml"))).isEqualTo("a: 1\n");
        assertThat(out.resolve("db/users.jsonl")).isRegularFile();
    }

    @Test
    void documentedCliSequenceRejectsTamperedFilesAndWrongPassphrase() throws Exception {
        assumeCli();
        byte[] archive = download(login("admin"), PASSPHRASE);
        Path work = Files.createTempDirectory("shipir-backup-cli");
        Path out = Files.createDirectory(work.resolve("restore"));
        Path good = work.resolve("good.enc");
        Files.write(good, archive);
        assertThat(runCli(good, out, "another passphrase!")).isNotZero();
        for (int pos : new int[]{40, archive.length / 2, archive.length - 1}) {
            byte[] bad = archive.clone();
            bad[pos] ^= 1;
            Path f = work.resolve("bad" + pos + ".enc");
            Files.write(f, bad);
            assertThat(runCli(f, out, PASSPHRASE)).as("flip at " + pos).isNotZero();
        }
        try (var files = Files.list(out)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void flippedCiphertextOrTrailerByteIsRejected() throws Exception {
        byte[] archive = download(login("admin"), PASSPHRASE);
        for (int pos : new int[]{16, archive.length / 2, archive.length - 41, archive.length - 33, archive.length - 1}) {
            byte[] bad = archive.clone();
            bad[pos] ^= 1;
            assertThatThrownBy(() -> decryptAndUntar(bad, PASSPHRASE)).as("flip at " + pos)
                    .isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void auditsExportWithoutPassphrase() throws Exception {
        jdbc.update("DELETE FROM audit_log WHERE action LIKE 'BACKUP_EXPORT%'");
        download(login("admin"), PASSPHRASE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'BACKUP_EXPORT' "
                + "AND outcome = 'SUCCESS' AND username = 'admin'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'BACKUP_EXPORT_RESULT' "
                + "AND outcome = 'SUCCESS' AND username = 'admin'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE details::text LIKE ? "
                + "OR target_id LIKE ?", Integer.class, "%" + PASSPHRASE + "%", "%" + PASSPHRASE + "%")).isZero();
    }

    @Test
    void requestToStringHidesPassphrase() {
        assertThat(new dev.bethge.shipir.backup.dto.BackupRequest(PASSPHRASE).toString()).doesNotContain(PASSPHRASE);
    }

    private static boolean exists(String cmd) {
        try {
            return new ProcessBuilder(cmd, "version").redirectErrorStream(true).start().waitFor() >= 0;
        } catch (Exception e) {
            return false;
        }
    }
}
