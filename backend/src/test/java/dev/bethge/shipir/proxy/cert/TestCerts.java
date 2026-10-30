package dev.bethge.shipir.proxy.cert;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Real certificates for tests, generated with the JDK's own {@code keytool} (no certificate builder library is a
 * dependency). Results are cached per parameter set because every call starts a JVM.
 */
final class TestCerts {

    /** PEM encoded certificate and PKCS#8 private key. */
    record Pair(String certPem, String keyPem) {
    }

    private static final Map<String, Pair> CACHE = new ConcurrentHashMap<>();

    private TestCerts() {
    }

    /** Valid for 30 days. {@code san} like {@code "dns:a.example.com,dns:*.b.example.com"} or null for none (CN only). */
    static Pair valid(String keyAlg, String cn, String san) {
        return generate(keyAlg, cn, san, "30", null, "2048");
    }

    static Pair rsa(int bits, String cn, String san) {
        return generate("RSA", cn, san, "30", null, String.valueOf(bits));
    }

    /** Expired a few days ago. */
    static Pair expired(String cn, String san) {
        return generate("RSA", cn, san, "1", "-10d", "2048");
    }

    static Pair notYetValid(String cn, String san) {
        return generate("RSA", cn, san, "30", "+5d", "2048");
    }

    private static Pair generate(String keyAlg, String cn, String san, String validityDays, String startDate, String keySize) {
        return CACHE.computeIfAbsent(String.join("|", keyAlg, cn, String.valueOf(san), validityDays, String.valueOf(startDate), keySize), k -> {
            try {
                Path dir = Files.createTempDirectory("shipir-testcert-");
                Path store = dir.resolve("k.p12");
                List<String> cmd = new ArrayList<>(List.of(
                        Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                        "-genkeypair", "-alias", "a", "-keyalg", keyAlg, "-dname", "CN=" + cn,
                        "-validity", validityDays, "-storetype", "PKCS12", "-keystore", store.toString(),
                        "-storepass", "changeit", "-keypass", "changeit"));
                if ("RSA".equals(keyAlg)) {
                    cmd.addAll(List.of("-keysize", keySize));
                } else if ("EC".equals(keyAlg)) {
                    cmd.addAll(List.of("-groupname", "secp256r1"));
                }
                if (san != null) {
                    cmd.addAll(List.of("-ext", "san=" + san));
                }
                if (startDate != null) {
                    cmd.addAll(List.of("-startdate", startDate));
                }
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String out;
                try (InputStream in = p.getInputStream()) {
                    out = new String(in.readAllBytes());
                }
                if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                    throw new IllegalStateException("keytool failed: " + out);
                }
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(store)) {
                    ks.load(in, "changeit".toCharArray());
                }
                Certificate cert = ks.getCertificate("a");
                Key key = ks.getKey("a", "changeit".toCharArray());
                return new Pair(pem("CERTIFICATE", cert.getEncoded()), pem("PRIVATE KEY", key.getEncoded()));
            } catch (IOException | java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
