package dev.bethge.shipir.compose;

import dev.bethge.shipir.common.net.EgressDeniedException;
import dev.bethge.shipir.common.net.EgressHttpClient;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One HTTP GET to a check's URL through {@link EgressHttpClient}: the egress policy validates the target inside the very
 * DNS resolution the connection uses (private ranges refused unless {@code shipir.egress.allow-private-ranges};
 * link-local and cloud metadata always), redirects are never followed (a 3xx is just a status), the body is capped at
 * 1 MiB and discarded, and a hard deadline of the timeout plus a second ends a slow-drip response. Results carry a fixed
 * error code, never exception text or the URL (it may hold secrets in its query).
 */
@Component
class UptimeProbe {

    record ProbeResult(boolean ok, Integer statusCode, Integer latencyMs, String errorCode) {
    }

    private static final Map<String, String> HEADERS = Map.of("User-Agent", "Shipir-Uptime", "Accept", "*/*");

    private final EgressHttpClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    UptimeProbe(EgressHttpClient client) {
        this.client = client;
    }

    /** @param expectedStatus exact status that counts as up, null = any 2xx */
    ProbeResult probe(String url, Integer expectedStatus, int timeoutSeconds) {
        long start = System.nanoTime();
        Future<EgressHttpClient.Response> call = executor.submit(
                () -> client.send("GET", URI.create(url), HEADERS, Duration.ofSeconds(timeoutSeconds)));
        try {
            EgressHttpClient.Response response = call.get(timeoutSeconds + 1L, TimeUnit.SECONDS);
            int latency = latencyMs(start);
            boolean ok = expectedStatus == null ? response.status() >= 200 && response.status() < 300
                    : response.status() == expectedStatus;
            return new ProbeResult(ok, response.status(), latency, ok ? null : "UNEXPECTED_STATUS");
        } catch (TimeoutException e) {
            call.cancel(true);
            return failure("TIMEOUT");
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return failure("INTERRUPTED");
        } catch (ExecutionException e) {
            return failure(codeOf(e.getCause()));
        } catch (RuntimeException e) {
            return failure("ERROR");
        }
    }

    private static ProbeResult failure(String code) {
        return new ProbeResult(false, null, null, code);
    }

    private static int latencyMs(long startNanos) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static String codeOf(Throwable error) {
        String code = "IO_ERROR";
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof EgressDeniedException) {
                return "EGRESS_DENIED";
            }
            if (t instanceof SocketTimeoutException || t instanceof java.io.InterruptedIOException) {
                return "TIMEOUT";
            }
            if (t instanceof ConnectException) {
                code = "CONNECTION_REFUSED";
            } else if (t instanceof UnknownHostException && "IO_ERROR".equals(code)) {
                code = "DNS_FAILED";
            } else if (t instanceof SSLException && "IO_ERROR".equals(code)) {
                code = "TLS_ERROR";
            } else if (!(t instanceof IOException) && "IO_ERROR".equals(code)) {
                code = "ERROR";
            }
        }
        return code;
    }
}
