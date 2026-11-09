package dev.bethge.shipir.common.net;

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP client for requests to user-influenced URLs. The {@link EgressPolicy} validates the address inside the DNS
 * resolution the connection then uses, so there is no gap for DNS rebinding. Redirects are never followed (callers see the
 * 3xx and decide), the body is capped, no proxy or cookies. Failures, including policy denials, are {@link IOException}s.
 */
@Component
public class EgressHttpClient implements AutoCloseable {

    static final int MAX_BODY_BYTES = 1_048_576;

    /**
     * How long a request waits for a pooled connection. One constant for every Apache client of this package family:
     * HttpClient 5 does not merge a per-request {@link RequestConfig} into the client default, so each place that sets a
     * per-request config must set this too, or it silently falls back to the 3 minute library default.
     */
    public static final Timeout CONNECTION_REQUEST_TIMEOUT = Timeout.ofSeconds(30);

    /** Streamed response: the body is open while the handler runs; {@link #abort()} unblocks a stalled read. */
    public static final class StreamResponse {
        private final int status;
        private final Map<String, String> headers;
        private final InputStream body;
        private final HttpUriRequestBase request;

        StreamResponse(int status, Map<String, String> headers, InputStream body, HttpUriRequestBase request) {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.request = request;
        }

        public int status() {
            return status;
        }

        /** First value of the header (case-insensitive), null if absent. */
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public InputStream body() {
            return body;
        }

        /** Cancels the request and closes its connection; a blocked read of {@link #body()} fails with an IOException. */
        public void abort() {
            request.abort();
        }
    }

    @FunctionalInterface
    public interface StreamHandler<T> {
        T handle(StreamResponse response) throws IOException;
    }

    /**
     * Status, first value of each header (lower-case name) and at most 1 MiB of the body as UTF-8.
     *
     * @param truncated true if the server sent more than the cap; {@code body} is then only a prefix and must not be
     *                  parsed or trusted as the whole answer (a prefix can be valid JSON on its own)
     */
    public record Response(int status, Map<String, String> headers, String body, boolean truncated) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private final CloseableHttpClient client;

    @Autowired
    public EgressHttpClient(EgressPolicy policy) {
        this(policy, Duration.ofSeconds(10));
    }

    /** @param connectTimeout bound for connect and TLS handshake; the per-call timeout bounds the response */
    public EgressHttpClient(EgressPolicy policy, Duration connectTimeout) {
        Timeout connect = Timeout.ofMilliseconds(connectTimeout.toMillis());
        this.client = HttpClientBuilder.create()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(policy.dnsResolver())
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(connect).setSocketTimeout(connect).build())
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .build();
    }

    public Response send(String method, URI uri, Map<String, String> headers, Duration timeout) throws IOException {
        return send(method, uri, headers, null, timeout);
    }

    /** Same, with a UTF-8 text body (null = none); set the Content-Type via {@code headers}. */
    public Response send(String method, URI uri, Map<String, String> headers, String requestBody, Duration timeout)
            throws IOException {
        HttpUriRequestBase request = new HttpUriRequestBase(method, uri);
        headers.forEach(request::addHeader);
        if (requestBody != null) {
            request.setEntity(new StringEntity(requestBody, StandardCharsets.UTF_8));
        }
        Timeout t = Timeout.ofMilliseconds(timeout.toMillis());
        request.setConfig(RequestConfig.custom().setResponseTimeout(t).setConnectionRequestTimeout(t).build());
        return client.execute(request, response -> {
            Map<String, String> out = new LinkedHashMap<>();
            for (Header h : response.getHeaders()) {
                out.putIfAbsent(h.getName().toLowerCase(Locale.ROOT), h.getValue());
            }
            String body = "";
            boolean truncated = false;
            if (response.getEntity() != null && !"HEAD".equalsIgnoreCase(method)) {
                try (InputStream in = response.getEntity().getContent()) {
                    // one byte more than the cap tells "exactly at the cap" from "cut off"
                    byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
                    truncated = bytes.length > MAX_BODY_BYTES;
                    body = new String(bytes, 0, Math.min(bytes.length, MAX_BODY_BYTES), StandardCharsets.UTF_8);
                }
                EntityUtils.consumeQuietly(response.getEntity());
            }
            return new Response(response.getCode(), out, body, truncated);
        });
    }

    /**
     * Sends the request and hands the still-open response to {@code handler} (no body cap: the handler reads what it
     * needs, e.g. server-sent events). The address is validated inside the lookup the connection uses (no rebinding
     * window), no redirects. {@code idleTimeout} bounds the wait for the response and each single read; a stalled read
     * fails with {@link java.net.SocketTimeoutException}. The connection is closed (not reused) when the handler returns.
     */
    public <T> T stream(String method, URI uri, Map<String, String> headers, String requestBody, Duration idleTimeout,
                        StreamHandler<T> handler) throws IOException {
        HttpUriRequestBase request = new HttpUriRequestBase(method, uri);
        headers.forEach(request::addHeader);
        if (requestBody != null) {
            request.setEntity(new StringEntity(requestBody, StandardCharsets.UTF_8));
        }
        request.setConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(idleTimeout.toMillis()))
                .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT)
                .build());
        var response = client.executeOpen(null, request, null);
        try {
            Map<String, String> out = new LinkedHashMap<>();
            for (Header h : response.getHeaders()) {
                out.putIfAbsent(h.getName().toLowerCase(Locale.ROOT), h.getValue());
            }
            InputStream body = response.getEntity() == null ? InputStream.nullInputStream()
                    : response.getEntity().getContent();
            return handler.handle(new StreamResponse(response.getCode(), out, body, request));
        } finally {
            try {
                response.close();
            } catch (IOException ignored) {
                // an aborted or failed response cannot be closed cleanly; the connection is gone either way
            }
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
