package dev.bethge.shipir.common.net;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EgressHttpClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            hits.add("ok");
            byte[] body = "hello".getBytes();
            ex.getResponseHeaders().add("X-Thing", "v");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/redirect", ex -> {
            hits.add("redirect");
            ex.getResponseHeaders().add("Location", "/ok");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/big", ex -> {
            byte[] body = new byte[3_000_000];
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void deniesLoopbackByDefaultAndNeverContactsTheServer() {
        EgressHttpClient client = new EgressHttpClient(new EgressPolicy(new EgressProperties(false)));
        assertThatThrownBy(() -> client.send("GET", uri("/ok"), Map.of(), TIMEOUT)).isInstanceOf(java.io.IOException.class);
        assertThat(hits).isEmpty();
    }

    @Test
    void reachesLoopbackWhenPrivateRangesAreAllowed() throws Exception {
        EgressHttpClient client = new EgressHttpClient(new EgressPolicy(new EgressProperties(true)));
        EgressHttpClient.Response r = client.send("GET", uri("/ok"), Map.of(), TIMEOUT);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("hello");
        assertThat(r.header("x-thing")).isEqualTo("v");
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        EgressHttpClient client = new EgressHttpClient(new EgressPolicy(new EgressProperties(true)));
        EgressHttpClient.Response r = client.send("GET", uri("/redirect"), Map.of(), TIMEOUT);
        assertThat(r.status()).isEqualTo(302);
        assertThat(hits).containsExactly("redirect");
    }

    @Test
    void connectsToTheAddressItValidatedWithoutResolvingAgain() throws Exception {
        // rebinding resolver: first answer is the (allowed) stub, every further lookup would point at the metadata IP
        AtomicInteger lookups = new AtomicInteger();
        EgressPolicy policy = new EgressPolicy(new EgressProperties(true), host -> {
            String ip = lookups.incrementAndGet() == 1 ? "127.0.0.1" : "169.254.169.254";
            return new InetAddress[]{InetAddress.getByName(ip)};
        });
        EgressHttpClient client = new EgressHttpClient(policy);
        URI target = URI.create("http://rebind.test:" + server.getAddress().getPort() + "/ok");
        assertThat(client.send("GET", target, Map.of(), TIMEOUT).status()).isEqualTo(200);
        assertThat(lookups).hasValue(1);
    }

    @Test
    void capsTheResponseBody() throws Exception {
        EgressHttpClient client = new EgressHttpClient(new EgressPolicy(new EgressProperties(true)));
        EgressHttpClient.Response r = client.send("GET", uri("/big"), Map.of(), TIMEOUT);
        assertThat(r.body().length()).isLessThanOrEqualTo(1_048_576);
    }

    @Test
    void streamHandsOverTheOpenBodyAndCanBeAbortedWhileBlocked() throws Exception {
        server.createContext("/slow", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write("data: 1\n\n".getBytes());
            ex.getResponseBody().flush();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                // test over
            }
            ex.close();
        });
        try (EgressHttpClient client = new EgressHttpClient(new EgressPolicy(new EgressProperties(true)))) {
            String first = client.stream("GET", uri("/slow"), Map.of(), null, Duration.ofSeconds(10), response -> {
                assertThat(response.status()).isEqualTo(200);
                assertThat(response.header("Content-Type")).isEqualTo("text/event-stream");
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(response.body()));
                String line = r.readLine();
                r.readLine(); // the blank line ending the event
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {
                        // fine
                    }
                    response.abort();
                });
                t.start();
                long start = System.nanoTime();
                try {
                    assertThat(r.readLine()).isNull(); // aborted: end of stream
                } catch (java.io.IOException aborted) {
                    // aborted: the blocked read fails
                }
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
                return line;
            });
            assertThat(first).isEqualTo("data: 1");
        }
    }

    @Test
    void streamIdleReadTimesOutAsSocketTimeout() throws Exception {
        server.createContext("/stall", ex -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                // test over
            }
            ex.close();
        });
        try (EgressHttpClient client = new EgressHttpClient(new EgressPolicy(new EgressProperties(true)))) {
            assertThatThrownBy(() -> client.stream("GET", uri("/stall"), Map.of(), null, Duration.ofMillis(300), r -> "x"))
                    .isInstanceOf(java.net.SocketTimeoutException.class);
        }
    }

    @Test
    void streamValidatesTheAddressInTheLookupItConnectsWith() throws Exception {
        AtomicInteger n = new AtomicInteger();
        EgressPolicy rebinding = new EgressPolicy(new EgressProperties(true), host -> host.equals("rebind.test")
                ? new InetAddress[]{InetAddress.getByName(n.getAndIncrement() == 0 ? "127.0.0.1" : "169.254.169.254")}
                : InetAddress.getAllByName(host));
        try (EgressHttpClient client = new EgressHttpClient(rebinding)) {
            int status = client.stream("GET",
                    URI.create("http://rebind.test:" + server.getAddress().getPort() + "/ok"), Map.of(), null,
                    TIMEOUT, r -> r.status());
            assertThat(status).isEqualTo(200);
            assertThat(n.get()).isEqualTo(1);
        }
    }
}
