package dev.bethge.shipir.backup;

import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.audit.AuditRecord;
import dev.bethge.shipir.audit.AuditService;
import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.compose.DeploymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Streams an encrypted backup: {@code tar.gz} of {@code manifest.json}, {@code db/<table>.jsonl} (one
 * {@code to_jsonb} row per line, read through a read-only JDBC transaction; secret columns stay the ciphertext
 * stored in the database) and {@code stacks/...} (the stacks base directory, symlinks skipped).
 *
 * <p>Encryption is the OpenSSL {@code enc} format ({@code "Salted__" + 8-byte salt + ciphertext}), AES-256-CBC with
 * key and IV from PBKDF2-HMAC-SHA256 ({@value #ITERATIONS} iterations) over the passphrase. Integrity is
 * encrypt-then-MAC: the file ends with a 40-byte trailer {@code macSalt(8) || HMAC-SHA256(macKey, everything before
 * the trailer)}. {@code macKey} is a second PBKDF2-HMAC-SHA256 derivation (same iterations) with the random
 * {@code macSalt}, so the passphrase is never used as HMAC key directly. A hex-key {@code openssl enc -P} run
 * reproduces that derivation without putting the passphrase into argv (only derived keys appear in argv).
 * Verify first, then decrypt (passphrase in {@code SHIPIR_BACKUP_PASSPHRASE}, OpenSSL 3+, F = the file; macOS ships
 * LibreSSL, so use Homebrew's {@code openssl@3} there, see README "Backup & restore"):
 * <pre>
 * N=$(( $(wc -c &lt; "$F") - 40 ))
 * MACSALT=$(tail -c 40 "$F" | head -c 8 | od -An -tx1 | tr -d ' \n')
 * WANT=$(tail -c 32 "$F" | od -An -tx1 | tr -d ' \n')
 * MACKEY=$(openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -S "$MACSALT" -P \
 *     -pass env:SHIPIR_BACKUP_PASSPHRASE | sed -n 's/^key=//p')
 * GOT=$(head -c "$N" "$F" | openssl dgst -sha256 -mac HMAC -macopt hexkey:"$MACKEY" -r | cut -d' ' -f1)
 * [ "$GOT" = "$WANT" ] || { echo "MAC mismatch: wrong passphrase or corrupted file" &gt;&amp;2; exit 1; }
 * head -c "$N" "$F" | openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 \
 *     -pass env:SHIPIR_BACKUP_PASSPHRASE | tar -xzf - -C restore-dir
 * </pre>
 * (An {@code openssl kdf ... PBKDF2} variant would need the passphrase as {@code -kdfopt pass:} in argv, so it is
 * not used.)
 *
 * <p>Nothing is buffered as a whole: tables are spooled one at a time to a temp file (a tar entry needs its size
 * first), files are copied in 64 KiB chunks. The passphrase is never logged.
 */
@Service
public class BackupService {

    static final int ITERATIONS = 600_000;
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9_]+");
    private static final byte[] MAGIC = "Salted__".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataSource dataSource;
    private final DeploymentProperties stacks;
    private final Clock clock;
    private final AuditService audit;
    private final UserRepository users;

    BackupService(DataSource dataSource, DeploymentProperties stacks, Clock clock, AuditService audit,
                  UserRepository users) {
        this.dataSource = dataSource;
        this.stacks = stacks;
        this.clock = clock;
        this.audit = audit;
        this.users = users;
    }

    /**
     * {@code BACKUP_EXPORT} is audited when accepted; the returned body writes the archive when the response is
     * streamed and then records {@code BACKUP_EXPORT_RESULT} (SUCCESS/FAILURE, never the passphrase).
     */
    @Auditable(action = "BACKUP_EXPORT", targetType = "BACKUP")
    public StreamingResponseBody export(char[] passphrase) {
        String username = SecurityContextHolder.getContext().getAuthentication() == null
                ? "system" : SecurityContextHolder.getContext().getAuthentication().getName();
        UUID userId = users.findByUsername(username).map(u -> u.getId()).orElse(null);
        return out -> {
            boolean success = false;
            try {
                write(out, passphrase);
                success = true;
            } catch (IOException | RuntimeException e) {
                log.warn("Backup export failed: {}", e.getClass().getSimpleName());
                throw e;
            } finally {
                recordResult(userId, username, success);
            }
        };
    }

    private void recordResult(UUID userId, String username, boolean success) {
        try {
            audit.record(new AuditRecord(userId, username, "BACKUP_EXPORT_RESULT",
                    success ? AuditLogEntry.Outcome.SUCCESS : AuditLogEntry.Outcome.FAILURE,
                    "BACKUP", null, Map.of()));
        } catch (RuntimeException e) {
            log.warn("Could not write audit entry BACKUP_EXPORT_RESULT: {}", e.toString());
        }
    }

    void write(OutputStream target, char[] passphrase) throws IOException {
        long now = clock.instant().getEpochSecond();
        byte[] macSalt = new byte[8];
        RANDOM.nextBytes(macSalt);
        Mac mac = macFor(passphrase, macSalt);
        OutputStream encrypted = encrypting(new MacOutputStream(target, mac), passphrase);
        try (GZIPOutputStream gzip = new GZIPOutputStream(new BufferedOutputStream(encrypted, 64 * 1024))) {
            TarWriter tar = new TarWriter(gzip);
            List<String> tables = new ArrayList<>();
            try (Connection c = dataSource.getConnection()) {
                c.setReadOnly(true);
                c.setAutoCommit(false);
                // One snapshot for all tables, so rows committed during the export cannot leave dangling FKs.
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try {
                    tables.addAll(tableNames(c));
                    for (String table : tables) {
                        exportTable(c, table, tar, now);
                    }
                } finally {
                    c.rollback();
                }
            } catch (SQLException e) {
                throw new IOException("Database export failed", e);
            }
            byte[] manifest = ("{\"format\":1,\"createdAt\":\"" + clock.instant() + "\",\"tables\":["
                    + String.join(",", tables.stream().map(t -> "\"" + t + "\"").toList()) + "]}\n")
                    .getBytes(StandardCharsets.UTF_8);
            tar.file("manifest.json", manifest.length, now, new java.io.ByteArrayInputStream(manifest));
            addStacks(tar, now);
            tar.finish();
        }
        target.write(macSalt);
        target.write(mac.doFinal());
        target.flush();
    }

    private static Mac macFor(char[] passphrase, byte[] macSalt) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, macSalt, ITERATIONS, 32 * 8);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IOException("MAC setup failed", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Feeds everything written into the MAC; {@link #close()} only flushes so the trailer can follow. */
    private static final class MacOutputStream extends OutputStream {
        private final OutputStream target;
        private final Mac mac;

        MacOutputStream(OutputStream target, Mac mac) {
            this.target = target;
            this.mac = mac;
        }

        @Override
        public void write(int b) throws IOException {
            mac.update((byte) b);
            target.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            mac.update(b, off, len);
            target.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }
    }

    private static OutputStream encrypting(OutputStream target, char[] passphrase) throws IOException {
        byte[] salt = new byte[8];
        RANDOM.nextBytes(salt);
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, 48 * 8);
        try {
            byte[] keyIv = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyIv, 0, 32, "AES"),
                    new IvParameterSpec(keyIv, 32, 16));
            target.write(MAGIC);
            target.write(salt);
            return new CipherOutputStream(target, cipher);
        } catch (GeneralSecurityException e) {
            throw new IOException("Encryption setup failed", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static List<String> tableNames(Connection c) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables "
                     + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' "
                     + "AND table_name <> 'flyway_schema_history' ORDER BY table_name")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (!SAFE_NAME.matcher(name).matches()) {
                    throw new SQLException("Unexpected table name");
                }
                names.add(name);
            }
        }
        return names;
    }

    private void exportTable(Connection c, String table, TarWriter tar, long now) throws SQLException, IOException {
        Path spool = Files.createTempFile("shipir-backup-", ".jsonl");
        try {
            try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(spool));
                 Statement st = c.createStatement()) {
                st.setFetchSize(500);
                try (ResultSet rs = st.executeQuery("SELECT to_jsonb(t)::text FROM \"" + table + "\" t")) {
                    while (rs.next()) {
                        o.write(rs.getString(1).getBytes(StandardCharsets.UTF_8));
                        o.write('\n');
                    }
                }
            }
            try (InputStream in = Files.newInputStream(spool)) {
                tar.file("db/" + table + ".jsonl", Files.size(spool), now, in);
            }
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    private void addStacks(TarWriter tar, long now) throws IOException {
        Path base = stacks.baseDir().toAbsolutePath().normalize();
        tar.directory("stacks", now);
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(base)) {
                    tar.directory(entryName(dir), attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.SECONDS));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
                    try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                        tar.file(entryName(file), attrs.size(),
                                attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.SECONDS), in);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            private String entryName(Path p) {
                return "stacks/" + base.relativize(p).toString().replace('\\', '/');
            }
        });
    }
}
