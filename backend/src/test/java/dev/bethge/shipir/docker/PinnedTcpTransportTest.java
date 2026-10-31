package dev.bethge.shipir.docker;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import dev.bethge.shipir.docker.DockerEndpoint.TlsMaterial;
import dev.bethge.shipir.docker.client.DockerSystemServiceImpl;
import dev.bethge.shipir.docker.dto.ConnectionTestResult;
import dev.bethge.shipir.docker.tls.TestPki;
import dev.bethge.shipir.docker.tls.TestPki.Issued;
import dev.bethge.shipir.docker.tls.TestPki.KeyType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import com.github.dockerjava.transport.DockerHttpClient;
import dev.bethge.shipir.docker.tls.PemSslConfig;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The TCP transport connects to the address the egress policy validated (one lookup per connection, no second
 * resolution by docker-java) and still verifies the TLS certificate against the configured host name. The daemon is a
 * JDK HttpsServer on loopback, the "DNS" is a scripted resolver, so no real network or DNS is involved.
 */
class PinnedTcpTransportTest {

    private static final String HOST = "docker.test";

    private final List<String> lookups = new CopyOnWriteArrayList<>();
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private HttpsServer server;
    private Issued ca;
    private Issued client;

    @BeforeEach
    void startDaemon() throws Exception {
        ca = TestPki.ca("pin-ca", KeyType.RSA);
        client = TestPki.client(ca, "pin-client", KeyType.RSA);
        server = serverFor(List.of(HOST));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpsServer serverFor(List<String> dnsNames) throws Exception {
        Issued cert = TestPki.server(ca, "daemon", KeyType.RSA, dnsNames, List.of());
        X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert.certPem().getBytes(StandardCharsets.UTF_8)));
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("server", cert.keyPair().getPrivate(), new char[0], new X509Certificate[]{x509});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, new char[0]);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);

        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(context));
        https.createContext("/", ex -> {
            hits.add(ex.getRequestURI().getPath());
            byte[] body = "{\"Version\":\"27.1.1\",\"ApiVersion\":\"1.46\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        https.start();
        return https;
    }

    /** {@code docker.test} answers with the given addresses, one answer per lookup; the last one repeats. */
    private DockerHostConnectionManager manager(boolean allowPrivate, String... answers) {
        EgressPolicy policy = new EgressPolicy(new EgressProperties(allowPrivate), host -> {
            lookups.add(host);
            String answer = answers[Math.min(lookups.size(), answers.length) - 1];
            return new InetAddress[]{InetAddress.getByName(answer)};
        });
        return new DockerHostConnectionManager(
                new DockerProperties("unix:///unused.sock", Duration.ofSeconds(2), Duration.ofSeconds(5)), policy);
    }

    private DockerEndpoint endpoint() {
        return new DockerEndpoint.Tcp(HOST, server.getAddress().getPort(),
                new TlsMaterial(ca.certPem(), client.certPem(), client.keyPkcs8Pem()));
    }

    private ConnectionTestResult ping(DockerHostConnectionManager manager) {
        return new DockerSystemServiceImpl(manager).ping(endpoint());
    }

    @Test
    void connectsToTheValidatedAddressWithExactlyOneLookup() {
        ConnectionTestResult result = ping(manager(true, "127.0.0.1"));

        assertThat(result.ok()).as(String.valueOf(result.message())).isTrue();
        assertThat(result.dockerVersion()).isEqualTo("27.1.1");
        assertThat(lookups).containsExactly(HOST);
        assertThat(hits).containsExactly("/version");
    }

    @Test
    void aRebindAfterTheFirstConnectionIsNeverFollowed() {
        DockerHostConnectionManager manager = manager(true, "127.0.0.1", "169.254.169.254");

        ConnectionTestResult first = ping(manager);
        ConnectionTestResult second = ping(manager);

        assertThat(first.ok()).isTrue();
        assertThat(second.ok()).isFalse();
        assertThat(second.errorCode()).isEqualTo(DockerErrorCode.UNREACHABLE);
        assertThat(lookups).hasSize(2);
        assertThat(hits).hasSize(1);
    }

    @Test
    void anAddressTheEgressPolicyDeniesIsNeverContacted() {
        ConnectionTestResult result = ping(manager(true, "169.254.169.254"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(DockerErrorCode.UNREACHABLE);
        assertThat(hits).isEmpty();
    }

    @Test
    void loopbackIsDeniedWhenPrivateRangesAreNotAllowed() {
        ConnectionTestResult result = ping(manager(false, "127.0.0.1"));

        assertThat(result.ok()).isFalse();
        assertThat(hits).isEmpty();
    }

    @Test
    void certificateIsStillVerifiedAgainstTheConfiguredNameNotTheAddress() throws Exception {
        server.stop(0);
        server = serverFor(List.of("other.test")); // valid certificate, but not for docker.test

        ConnectionTestResult result = ping(manager(true, "127.0.0.1"));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(DockerErrorCode.TLS_ERROR);
        assertThat(hits).isEmpty();
    }

    // --- raw socket daemon: hijacked streams and incremental responses through PinnedTcpDockerHttpClient ---

    @FunctionalInterface
    private interface RawHandler {
        void handle(InputStream in, OutputStream out) throws Exception;
    }

    private SSLServerSocket rawServer;
    private Thread rawThread;

    /** TLS server socket on loopback (certificate for docker.test) that serves one connection with {@code handler}. */
    private int startRaw(RawHandler handler) throws Exception {
        Issued cert = TestPki.server(ca, "daemon", KeyType.RSA, List.of(HOST), List.of());
        X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert.certPem().getBytes(StandardCharsets.UTF_8)));
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("server", cert.keyPair().getPrivate(), new char[0], new X509Certificate[]{x509});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, new char[0]);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        rawServer = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        rawThread = new Thread(() -> {
            try (Socket socket = rawServer.accept()) {
                handler.handle(socket.getInputStream(), socket.getOutputStream());
            } catch (Exception ignored) {
                // the test asserts on what the client saw
            }
        });
        rawThread.setDaemon(true);
        rawThread.start();
        return rawServer.getLocalPort();
    }

    @AfterEach
    void stopRaw() throws Exception {
        if (rawServer != null) {
            rawServer.close();
        }
    }

    private PinnedTcpDockerHttpClient rawClient(int port) {
        EgressPolicy policy = new EgressPolicy(new EgressProperties(true),
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        return new PinnedTcpDockerHttpClient(URI.create("tcp://" + HOST + ":" + port),
                new PemSslConfig(new TlsMaterial(ca.certPem(), client.certPem(), client.keyPkcs8Pem())),
                Duration.ofSeconds(2), Duration.ofSeconds(5), policy.dnsResolver());
    }

    private static void readHeaders(InputStream in) throws Exception {
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                return;
            }
            state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1
                    : (b == '\r' ? 1 : 0);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }

    @Test
    void hijackedExchangeSendsStdinAndStreamsTheDaemonOutput() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        int port = startRaw((in, out) -> {
            readHeaders(in);
            out.write("HTTP/1.1 101 UPGRADED\r\nConnection: Upgrade\r\nUpgrade: tcp\r\n\r\nHELLO".getBytes(StandardCharsets.UTF_8));
            out.flush();
            received.set(new String(in.readNBytes(4), StandardCharsets.UTF_8));
            out.write("pong".getBytes(StandardCharsets.UTF_8));
            out.flush();
        });
        try (PinnedTcpDockerHttpClient http = rawClient(port);
             DockerHttpClient.Response response = http.execute(DockerHttpClient.Request.builder()
                     .method(DockerHttpClient.Request.Method.POST).path("/exec/x/start")
                     .hijackedInput(new ByteArrayInputStream("ping".getBytes(StandardCharsets.UTF_8))).build())) {
            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(new String(readAll(response.getBody()), StandardCharsets.UTF_8)).isEqualTo("HELLOpong");
        }
        rawThread.join(5000);
        assertThat(received.get()).isEqualTo("ping");
    }

    @Test
    void hijackedRequestAnsweredWithoutSwitchingProtocolsFails() throws Exception {
        int port = startRaw((in, out) -> {
            readHeaders(in);
            out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        });
        try (PinnedTcpDockerHttpClient http = rawClient(port)) {
            assertThatThrownBy(() -> http.execute(DockerHttpClient.Request.builder()
                    .method(DockerHttpClient.Request.Method.POST).path("/exec/x/start")
                    .hijackedInput(new ByteArrayInputStream(new byte[0])).build()))
                    .hasStackTraceContaining("Expected 101");
        }
    }

    @Test
    void chunkedResponseIsReadableBeforeTheNextChunkArrives() throws Exception {
        CountDownLatch firstRead = new CountDownLatch(1);
        int port = startRaw((in, out) -> {
            readHeaders(in);
            out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (firstRead.await(5, TimeUnit.SECONDS)) {
                out.write("5\r\nworld\r\n0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });
        try (PinnedTcpDockerHttpClient http = rawClient(port);
             DockerHttpClient.Response response = http.execute(DockerHttpClient.Request.builder()
                     .method(DockerHttpClient.Request.Method.GET).path("/containers/x/logs").build())) {
            InputStream body = response.getBody();
            assertThat(new String(body.readNBytes(5), StandardCharsets.UTF_8)).isEqualTo("hello");
            firstRead.countDown();
            assertThat(new String(readAll(body), StandardCharsets.UTF_8)).isEqualTo("world");
        }
    }
}
