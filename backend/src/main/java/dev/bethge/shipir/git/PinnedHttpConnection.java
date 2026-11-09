package dev.bethge.shipir.git;

import dev.bethge.shipir.common.net.EgressHttpClient;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.eclipse.jgit.transport.http.HttpConnection;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * JGit {@link HttpConnection} that connects only to the addresses the egress policy validated when the connection was
 * created ({@link EgressGuardedConnectionFactory}). JGit's default connection resolves the name again at connect time,
 * so a DNS answer that flips in between could reach an internal host; here the name is never resolved a second time:
 * the Apache client's resolver returns exactly the validated addresses, while TLS still verifies the certificate
 * against the original host name (SNI and hostname check use the URL host, not the IP).
 * <p>
 * Differences from the JDK connection, on purpose: no redirects (JGit follows them itself with a new, re-validated
 * connection), no proxy, TLS verification can never be switched off ({@link #configure} and
 * {@link #setHostnameVerifier} are ignored, so {@code http.sslVerify=false} makes the call fail instead of becoming
 * insecure), request bodies are buffered (capped), the response is not decompressed (JGit handles gzip itself).
 * <p>
 * One Apache client (pool of one) per request, on purpose: the pinned addresses belong to one validated connection
 * and JGit creates a new connection object, and so a fresh egress check, for every request (redirects included). A
 * pooled client shared across requests would keep sockets validated for an earlier answer alive, which is the window
 * this class closes; the cost is a new TCP/TLS handshake per request of a fetch (accepted: fetches are rare and short).
 */
final class PinnedHttpConnection implements HttpConnection {

    private static final int MAX_REQUEST_BODY = 64 * 1024 * 1024;

    private final URL url;
    private final InetAddress[] addresses;
    private final Duration defaultConnectTimeout;
    private final Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
    private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();

    private String method = "GET";
    private boolean doOutput;
    private int connectTimeoutMillis;
    private int readTimeoutMillis;

    private CloseableHttpClient client;
    private ClassicHttpResponse response;
    private int status;
    private String reason;
    private Map<String, List<String>> responseHeaders = Map.of();

    PinnedHttpConnection(URL url, InetAddress[] addresses, Duration defaultConnectTimeout) {
        this.url = url;
        this.addresses = addresses.clone();
        this.defaultConnectTimeout = defaultConnectTimeout;
    }

    /** The per-request config: the read timeout and the shared pool wait (HttpClient 5 does not merge defaults). */
    static RequestConfig requestConfig(int readTimeoutMillis) {
        RequestConfig.Builder builder = RequestConfig.custom()
                .setConnectionRequestTimeout(EgressHttpClient.CONNECTION_REQUEST_TIMEOUT);
        if (readTimeoutMillis > 0) {
            builder.setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMillis));
        }
        return builder.build();
    }

    private synchronized void execute() throws IOException {
        if (response != null || executedWithoutBody) {
            return;
        }
        if (failure != null) {
            throw failure; // a failed request is never sent a second time
        }
        try {
            send();
        } catch (IOException | RuntimeException e) {
            failure = e instanceof IOException io ? io : new IOException(e);
            throw failure;
        }
    }

    private IOException failure;

    private void send() throws IOException {
        HttpUriRequestBase request;
        try {
            request = new HttpUriRequestBase(method, url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URL", e);
        }
        requestHeaders.forEach((name, values) -> {
            if (!isManagedByClient(name)) {
                values.forEach(v -> request.addHeader(name, v));
            }
        });
        if (doOutput || requestBody.size() > 0) {
            request.setEntity(new ByteArrayEntity(requestBody.toByteArray(), null));
        }
        request.setConfig(requestConfig(readTimeoutMillis));
        Timeout connect = connectTimeoutMillis > 0 ? Timeout.ofMilliseconds(connectTimeoutMillis)
                : Timeout.ofMilliseconds(defaultConnectTimeout.toMillis());
        client = HttpClientBuilder.create()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(1)
                        .setDnsResolver(pinnedResolver())
                        .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(connect).build())
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableContentCompression()
                .build();
        try {
            response = client.executeOpen(null, request, null);
        } catch (IOException | RuntimeException e) {
            closeQuietly();
            throw e;
        }
        status = response.getCode();
        reason = response.getReasonPhrase();
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header h : response.getHeaders()) {
            headers.computeIfAbsent(h.getName(), k -> new ArrayList<>()).add(h.getValue());
        }
        responseHeaders = headers;
        if (status >= 300) {
            // errors and redirects carry nothing JGit reads; release the connection right away
            closeQuietly();
            response = null;
            executedWithoutBody = true;
        }
    }

    private boolean executedWithoutBody;

    private DnsResolver pinnedResolver() {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                // this connection talks to one host only, and only to the addresses validated at creation
                return addresses.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    private static boolean isManagedByClient(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("host") || n.equals("content-length") || n.equals("transfer-encoding")
                || n.equals("connection");
    }

    private void closeQuietly() {
        try {
            if (response != null) {
                response.close();
            }
        } catch (IOException ignored) {
            // nothing left to release
        }
        try {
            if (client != null) {
                client.close();
            }
        } catch (IOException ignored) {
            // nothing left to release
        }
    }

    @Override
    public int getResponseCode() throws IOException {
        execute();
        return status;
    }

    @Override
    public URL getURL() {
        return url;
    }

    @Override
    public String getResponseMessage() throws IOException {
        execute();
        return reason;
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
        try {
            execute();
        } catch (IOException e) {
            return Map.of();
        }
        return responseHeaders;
    }

    @Override
    public void setRequestProperty(String key, String value) {
        List<String> values = new ArrayList<>();
        values.add(value);
        requestHeaders.keySet().removeIf(k -> k.equalsIgnoreCase(key));
        requestHeaders.put(key, values);
    }

    @Override
    public void setRequestMethod(String method) {
        this.method = method;
    }

    @Override
    public void setUseCaches(boolean useCaches) {
        // no cache in this client
    }

    @Override
    public void setConnectTimeout(int millis) {
        this.connectTimeoutMillis = millis;
    }

    @Override
    public void setReadTimeout(int millis) {
        this.readTimeoutMillis = millis;
    }

    @Override
    public String getContentType() {
        return getHeaderField("Content-Type");
    }

    @Override
    public InputStream getInputStream() throws IOException {
        execute();
        if (status == 404 || status == 410) {
            throw new FileNotFoundException(url.toString());
        }
        if (status >= 400) {
            throw new IOException("Server returned HTTP response code: " + status);
        }
        if (response == null || response.getEntity() == null) {
            return InputStream.nullInputStream();
        }
        return new FilterInputStream(response.getEntity().getContent()) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    closeQuietly();
                }
            }
        };
    }

    @Override
    public String getHeaderField(String name) {
        List<String> values = getHeaderFields(name);
        return values.isEmpty() ? null : values.getLast();
    }

    @Override
    public List<String> getHeaderFields(String name) {
        List<String> values = getHeaderFields().get(name);
        return values == null ? List.of() : values;
    }

    @Override
    public int getContentLength() {
        String length = getHeaderField("Content-Length");
        try {
            return length == null ? -1 : Integer.parseInt(length.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public void setInstanceFollowRedirects(boolean followRedirects) {
        // never: JGit follows redirects itself through a new connection, which the egress policy validates again
    }

    @Override
    public void setDoOutput(boolean dooutput) {
        this.doOutput = dooutput;
    }

    @Override
    public void setFixedLengthStreamingMode(int contentLength) {
        // the body is buffered and sent with its exact length
    }

    @Override
    public OutputStream getOutputStream() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                limit(1);
                requestBody.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                limit(len);
                requestBody.write(b, off, len);
            }

            private void limit(int more) throws IOException {
                if ((long) requestBody.size() + more > MAX_REQUEST_BODY) {
                    throw new IOException("Request body too large");
                }
            }
        };
    }

    @Override
    public void setChunkedStreamingMode(int chunklen) {
        // the body is buffered and sent with its exact length
    }

    @Override
    public String getRequestMethod() {
        return method;
    }

    @Override
    public boolean usingProxy() {
        return false;
    }

    @Override
    public void connect() {
        // the connection is made with the request (the body is not known yet)
    }

    @Override
    public void configure(KeyManager[] km, TrustManager[] tm, SecureRandom random) {
        // ignored: certificates are always verified with the default trust store
    }

    @Override
    public void setHostnameVerifier(HostnameVerifier hostnameverifier) {
        // ignored: the hostname is always verified
    }
}
