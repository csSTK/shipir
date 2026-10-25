package dev.bethge.shipir.compose;

import com.sun.net.httpserver.HttpServer;
import dev.bethge.shipir.common.net.EgressHttpClient;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The HTTP probe against JDK HttpServer stubs: up, down, timeout, redirects, status expectations and the SSRF guard. */
class UptimeProbeTest {

    private HttpServer server;
    private HttpServer other;
    private final AtomicInteger otherHits = new AtomicInteger();
    private volatile int status = 200;
    private volatile long delayMillis;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (ex.getRequestURI().getPath().equals("/redirect")) {
                ex.getResponseHeaders().add("Location", "http://localhost:" + other.getAddress().getPort() + "/target");
                ex.sendResponseHeaders(302, -1);
            } else {
                byte[] body = "ok".getBytes();
                ex.sendResponseHeaders(status, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        other.createContext("/", ex -> {
            otherHits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        other.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        other.stop(0);
    }

    private static UptimeProbe probe(boolean allowPrivate) {
        EgressPolicy policy = new EgressPolicy(new EgressProperties(allowPrivate));
        return new UptimeProbe(new EgressHttpClient(policy, Duration.ofSeconds(2)));
    }

    @Test
    void status200IsUpAndRecordsStatusAndLatency() {
        UptimeProbe.ProbeResult r = probe(true).probe(base + "/", null, 5);
        assertThat(r.ok()).isTrue();
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(r.errorCode()).isNull();
    }

    @Test
    void status500IsDownWithUnexpectedStatus() {
        status = 500;
        UptimeProbe.ProbeResult r = probe(true).probe(base + "/", null, 5);
        assertThat(r.ok()).isFalse();
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.errorCode()).isEqualTo("UNEXPECTED_STATUS");
    }

    @Test
    void expectedStatusMustMatchExactly() {
        status = 204;
        assertThat(probe(true).probe(base + "/", 204, 5).ok()).isTrue();
        assertThat(probe(true).probe(base + "/", 200, 5).ok()).isFalse();
    }

    @Test
    void slowResponseTimesOutWithTimeoutCode() {
        delayMillis = 3_000;
        long start = System.nanoTime();
        UptimeProbe.ProbeResult r = probe(true).probe(base + "/", null, 1);
        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("TIMEOUT");
        assertThat(r.statusCode()).isNull();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void redirectToAnotherHostIsNotFollowed() {
        UptimeProbe.ProbeResult r = probe(true).probe(base + "/redirect", null, 5);
        assertThat(r.ok()).isFalse();
        assertThat(r.statusCode()).isEqualTo(302);
        assertThat(otherHits).hasValue(0);
    }

    @Test
    void redirectCanBeTheExpectedStatus() {
        assertThat(probe(true).probe(base + "/redirect", 302, 5).ok()).isTrue();
        assertThat(otherHits).hasValue(0);
    }

    @Test
    void refusedConnectionIsReportedWithAFixedCode() {
        int closedPort = other.getAddress().getPort();
        other.stop(0);
        UptimeProbe.ProbeResult r = probe(true).probe("http://127.0.0.1:" + closedPort + "/", null, 5);
        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("CONNECTION_REFUSED");
    }

    @Test
    void privateTargetIsRefusedUnderTheDefaultPolicy() {
        UptimeProbe.ProbeResult r = probe(false).probe(base + "/", null, 5);
        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("EGRESS_DENIED");
        assertThat(r.statusCode()).isNull();
    }

    @Test
    void metadataAddressIsRefusedEvenWhenPrivateRangesAreAllowed() {
        UptimeProbe.ProbeResult r = probe(true).probe("http://169.254.169.254/latest/meta-data", null, 2);
        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("EGRESS_DENIED");
    }

    @Test
    void errorCodesNeverCarryTheUrl() {
        UptimeProbe.ProbeResult r = probe(false).probe(base + "/?token=secret", null, 5);
        assertThat(String.valueOf(r)).doesNotContain("secret");
    }
}
