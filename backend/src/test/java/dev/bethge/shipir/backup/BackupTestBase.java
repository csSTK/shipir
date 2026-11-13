package dev.bethge.shipir.backup;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
abstract class BackupTestBase {

    static final String PASSWORD = "A-test-Passw0rd-for-backup!";
    static final String PASSPHRASE = "correct horse battery staple";
    static final Path STACKS;

    static {
        try {
            STACKS = Files.createTempDirectory("shipir-backup-test-stacks");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("shipir.stacks.base-dir", STACKS::toString);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void resetState() {
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase();
            User u = users.findByUsername(name).orElseGet(() -> new User(name, "x", role));
            u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
            users.save(u);
        }
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Backup host', 'LOCAL', id FROM users WHERE username = 'admin'");
    }

    record Login(MockHttpSession session, Cookie csrf) {
        MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
            return b.session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    Login login(String username) throws Exception {
        Cookie anon = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login")
                .cookie(anon).header("X-XSRF-TOKEN", anon.getValue())
                .contentType(APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Login((MockHttpSession) r.getRequest().getSession(false), anon);
    }

    /** POST /api/backup and return the fully streamed response bytes. */
    byte[] download(Login l, String passphrase) throws Exception {
        MvcResult started = mvc.perform(l.write(post("/api/backup")).contentType(APPLICATION_JSON)
                .content("{\"passphrase\":\"%s\",\"stepUp\":{\"password\":\"%s\"}}".formatted(passphrase, PASSWORD))).andReturn();
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        MvcResult done = mvc.perform(asyncDispatch(started)).andReturn();
        return done.getResponse().getContentAsByteArray();
    }

    /** Independent decryptor: the same steps as the documented openssl command, then gunzip and untar. */
    static Map<String, byte[]> decryptAndUntar(byte[] archive, String passphrase) throws Exception {
        int n = archive.length - 40;
        byte[] macKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(passphrase.toCharArray(), Arrays.copyOfRange(archive, n, n + 8),
                        600_000, 256)).getEncoded();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
        mac.update(archive, 0, n);
        if (!MessageDigest.isEqual(mac.doFinal(), Arrays.copyOfRange(archive, n + 8, archive.length))) {
            throw new SecurityException("MAC mismatch");
        }
        assertThat(Arrays.copyOf(archive, 8)).isEqualTo("Salted__".getBytes(StandardCharsets.US_ASCII));
        byte[] salt = Arrays.copyOfRange(archive, 8, 16);
        byte[] keyIv = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(passphrase.toCharArray(), salt, 600_000, 384)).getEncoded();
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyIv, 0, 32, "AES"), new IvParameterSpec(keyIv, 32, 16));
        byte[] plain = c.doFinal(archive, 16, n - 16);
        return untar(new GZIPInputStream(new ByteArrayInputStream(plain)));
    }

    static Map<String, byte[]> untar(InputStream in) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        byte[] h = new byte[512];
        while (in.readNBytes(h, 0, 512) == 512 && h[0] != 0) {
            String name = new String(h, 0, 100, StandardCharsets.US_ASCII).trim().replace("\0", "");
            String prefix = new String(h, 345, 155, StandardCharsets.US_ASCII).trim().replace("\0", "");
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
            long size = Long.parseLong(new String(h, 124, 11, StandardCharsets.US_ASCII).trim(), 8);
            byte[] data = in.readNBytes((int) size);
            in.skipNBytes((512 - size % 512) % 512);
            if (h[156] == '0') {
                files.put(name, data);
            }
        }
        return files;
    }
}
