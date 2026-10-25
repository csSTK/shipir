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

    /** Status, first value of each header (lower-case name) and at most 1 MiB of the body as UTF-8. */
    public record Response(int status, Map<String, String> headers, String body) {

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
            if (response.getEntity() != null && !"HEAD".equalsIgnoreCase(method)) {
                try (InputStream in = response.getEntity().getContent()) {
                    body = new String(in.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
                }
                EntityUtils.consumeQuietly(response.getEntity());
            }
            return new Response(response.getCode(), out, body);
        });
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
