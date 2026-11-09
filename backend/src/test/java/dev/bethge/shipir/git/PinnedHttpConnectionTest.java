package dev.bethge.shipir.git;

import com.sun.net.httpserver.HttpServer;
import dev.bethge.shipir.common.net.EgressHttpClient;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import org.apache.hc.core5.util.Timeout;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pinned JGit connection against a loopback stub; the name never resolves a second time. */
class PinnedHttpConnectionTest {

    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> postBody = new AtomicReference<>();
    private final AtomicReference<String> seenHost = new AtomicReference<>();
    private final AtomicReference<String> seenCustom = new AtomicReference<>();
    private AtomicInteger lookups;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            hits.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            seenHost.set(ex.getRequestHeaders().getFirst("Host"));
            seenCustom.set(ex.getRequestHeaders().getFirst("X-Custom"));
            postBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "answer".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/x-git-upload-pack-advertisement");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.createContext("/denied", ex -> {
            ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"x\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** rebind.test resolves to loopback the first time and to the metadata address every later time. */
    private EgressGuardedConnectionFactory factory() {
        AtomicInteger lookups = new AtomicInteger();
        this.lookups = lookups;
        EgressPolicy policy = new EgressPolicy(new EgressProperties(true), host -> host.equals("rebind.test")
                ? new InetAddress[]{InetAddress.getByName(lookups.getAndIncrement() == 0 ? "127.0.0.1" : "169.254.169.254")}
                : InetAddress.getAllByName(host));
        return new EgressGuardedConnectionFactory(policy, Duration.ofSeconds(2));
    }

    private URL url(String path) throws Exception {
        return new URL("http://rebind.test:" + server.getAddress().getPort() + path);
    }

    @Test
    void connectsToTheValidatedAddressAndNeverResolvesTheNameAgain() throws Exception {
        HttpConnection c = factory().create(url("/ok?service=git-upload-pack"));
        c.setRequestMethod("GET");
        c.setRequestProperty("X-Custom", "v");

        assertThat(c.getResponseCode()).isEqualTo(200);
        assertThat(new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("answer");
        assertThat(c.getContentType()).isEqualTo("application/x-git-upload-pack-advertisement");
        assertThat(hits).containsExactly("GET /ok?service=git-upload-pack");
        assertThat(seenHost.get()).startsWith("rebind.test:");
        assertThat(seenCustom.get()).isEqualTo("v");
        assertThat(lookups.get()).isEqualTo(1);
    }

    @Test
    void aNewConnectionAfterTheRebindIsRefused() throws Exception {
        EgressGuardedConnectionFactory f = factory();
        f.create(url("/ok"));

        assertThatThrownBy(() -> f.create(url("/ok"))).isInstanceOf(java.io.IOException.class);
        assertThat(hits).isEmpty();
    }

    @Test
    void postBodyIsSentWithItsContent() throws Exception {
        HttpConnection c = factory().create(url("/ok"));
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-git-upload-pack-request");
        c.setFixedLengthStreamingMode(4);
        c.getOutputStream().write("want".getBytes(StandardCharsets.UTF_8));

        assertThat(c.getResponseCode()).isEqualTo(200);
        assertThat(postBody.get()).isEqualTo("want");
    }

    @Test
    void notFoundSurfacesAsFileNotFoundAndKeepsHeadersForAuthStatus() throws Exception {
        HttpConnection missing = factory().create(url("/missing"));
        assertThat(missing.getResponseCode()).isEqualTo(404);
        assertThatThrownBy(missing::getInputStream).isInstanceOf(FileNotFoundException.class);

        HttpConnection unauthorized = factory().create(url("/denied"));
        assertThat(unauthorized.getResponseCode()).isEqualTo(401);
        assertThat(unauthorized.getHeaderField("www-authenticate")).isEqualTo("Basic realm=\"x\"");
    }

    @Test
    void nonHttpProtocolsAreRefused() {
        assertThatThrownBy(() -> factory().create(new URL("file:///etc/passwd"))).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void perRequestConfigKeepsTheSharedConnectionRequestTimeout() {
        // HttpClient 5 does not merge request and default config: the per-request one must carry the value itself
        assertThat(PinnedHttpConnection.requestConfig(5000).getConnectionRequestTimeout())
                .isEqualTo(EgressHttpClient.CONNECTION_REQUEST_TIMEOUT);
        assertThat(PinnedHttpConnection.requestConfig(5000).getResponseTimeout()).isEqualTo(Timeout.ofMilliseconds(5000));
        assertThat(PinnedHttpConnection.requestConfig(0).getConnectionRequestTimeout())
                .isEqualTo(EgressHttpClient.CONNECTION_REQUEST_TIMEOUT);
    }

    @Test
    void sslVerificationCannotBeSwitchedOff(@TempDir java.nio.file.Path dir) throws Exception {
        // HTTPS stub with a self-signed certificate (valid for rebind.test, so only the missing trust can fail it)
        java.nio.file.Path keystore = dir.resolve("ks.p12");
        Process keytool = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=rebind.test", "-ext", "SAN=dns:rebind.test", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", "changeit").redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();
        assertThat(keytool.waitFor(60, TimeUnit.SECONDS)).as("keytool finished in time").isTrue();
        assertThat(keytool.exitValue()).isZero();
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(keystore)) {
            ks.load(in, "changeit".toCharArray());
        }
        javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        javax.net.ssl.SSLContext tls = javax.net.ssl.SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        com.sun.net.httpserver.HttpsServer https = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tls));
        https.createContext("/ok", ex -> {
            hits.add("https");
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        https.start();
        try {
            EgressGuardedConnectionFactory f = factory();
            HttpConnection c = f.create(new URL("https://rebind.test:" + https.getAddress().getPort() + "/ok"));
            javax.net.ssl.TrustManager trustAll = new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            };
            c.configure(null, new javax.net.ssl.TrustManager[]{trustAll}, new java.security.SecureRandom());
            c.setHostnameVerifier((h, sess) -> true);
            HttpConnection same = f.newSession().configure(c, false);

            assertThat(same).isSameAs(c);
            assertThatThrownBy(c::getResponseCode).isInstanceOf(javax.net.ssl.SSLException.class);
            assertThat(hits).isEmpty();
        } finally {
            https.stop(0);
        }
    }
}
