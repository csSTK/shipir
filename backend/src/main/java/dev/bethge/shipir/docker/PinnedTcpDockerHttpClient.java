package dev.bethge.shipir.docker;

import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.transport.SSLConfig;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.impl.DefaultContentLengthStrategy;
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.http.io.HttpResponseInformationCallback;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.AbstractHttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EmptyInputStream;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * docker-java transport for TCP daemons whose name is resolved through a caller supplied {@link DnsResolver}.
 * <p>
 * docker-java's own httpclient5 transport hard-wires the system resolver, so the egress policy could only check the name
 * and docker-java would resolve it again when connecting (DNS rebinding window). Here the resolver passed in is the
 * egress policy's: it resolves once per connection, validates every address and connects to exactly those. The
 * {@link HttpHost} keeps the configured name, so SNI and certificate host name verification still use the name, not the
 * address. Behaviour otherwise mirrors docker-java's {@code ApacheDockerHttpClientImpl} (TCP only), including the
 * hijacked stream used by attach/exec.
 */
final class PinnedTcpDockerHttpClient implements DockerHttpClient {

    private static final String HIJACKED_INPUT = "com.github.docker-java.hijackedInput";

    private final CloseableHttpClient httpClient;
    private final HttpHost host;
    private final String pathPrefix;

    PinnedTcpDockerHttpClient(URI dockerHost, SSLConfig sslConfig, Duration connectTimeout, Duration responseTimeout,
                              DnsResolver dns) {
        String path = dockerHost.getRawPath();
        this.pathPrefix = path == null ? "" : path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        this.host = new HttpHost(sslConfig == null ? "http" : "https", dockerHost.getHost(), dockerHost.getPort());

        var tls = sslConfig == null ? DefaultClientTlsStrategy.createSystemDefault()
                : new DefaultClientTlsStrategy(sslConfigContext(sslConfig));
        PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dns)
                .setTlsSocketStrategy(tls)
                .setConnectionFactory(new ManagedHttpClientConnectionFactory(null, null, null, null,
                        message -> {
                            Header te = message.getFirstHeader("Transfer-Encoding");
                            if (te != null && "identity".equalsIgnoreCase(te.getValue())) {
                                return org.apache.hc.core5.http.ContentLengthStrategy.UNDEFINED;
                            }
                            return DefaultContentLengthStrategy.INSTANCE.determineLength(message);
                        }, null))
                .setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.ZERO_MILLISECONDS).build())
                .setMaxConnTotal(Integer.MAX_VALUE)
                .setMaxConnPerRoute(Integer.MAX_VALUE)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setValidateAfterInactivity(org.apache.hc.core5.util.TimeValue.NEG_ONE_SECOND)
                        .setConnectTimeout(connectTimeout == null ? null
                                : Timeout.of(connectTimeout.toNanos(), TimeUnit.NANOSECONDS))
                        .build())
                .build();
        this.httpClient = HttpClients.custom()
                .setRequestExecutor(new HijackingRequestExecutor())
                .setConnectionManager(manager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(responseTimeout == null ? null
                                : Timeout.of(responseTimeout.toNanos(), TimeUnit.NANOSECONDS))
                        .build())
                .disableConnectionState()
                .build();
    }

    private static javax.net.ssl.SSLContext sslConfigContext(SSLConfig sslConfig) {
        try {
            return sslConfig.getSSLContext();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the TLS context.", e);
        }
    }

    @Override
    public Response execute(Request request) {
        HttpCoreContext context = new HttpCoreContext();
        HttpUriRequestBase httpRequest = new HttpUriRequestBase(request.method(),
                URI.create(pathPrefix + request.path()));
        httpRequest.setScheme(host.getSchemeName());
        httpRequest.setAuthority(new URIAuthority(host.getHostName(), host.getPort()));
        request.headers().forEach(httpRequest::setHeader);

        byte[] bodyBytes = request.bodyBytes();
        if (bodyBytes != null) {
            httpRequest.setEntity(new ByteArrayEntity(bodyBytes, null));
        } else {
            InputStream body = request.body();
            if (body != null) {
                httpRequest.setEntity(new InputStreamEntity(body, null));
            }
        }
        if (request.hijackedInput() != null) {
            context.setAttribute(HIJACKED_INPUT, request.hijackedInput());
            httpRequest.setHeader("Upgrade", "tcp");
            httpRequest.setHeader("Connection", "Upgrade");
        }
        try {
            return new PinnedResponse(httpRequest, httpClient.executeOpen(host, httpRequest, context));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }

    private static final class PinnedResponse implements Response {

        private static final Logger LOGGER = LoggerFactory.getLogger(PinnedResponse.class);

        private final HttpUriRequestBase request;
        private final ClassicHttpResponse response;

        PinnedResponse(HttpUriRequestBase request, ClassicHttpResponse response) {
            this.request = request;
            this.response = response;
        }

        @Override
        public int getStatusCode() {
            return response.getCode();
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            return Stream.of(response.getHeaders()).collect(Collectors.groupingBy(Header::getName,
                    Collectors.mapping(Header::getValue, Collectors.toList())));
        }

        @Override
        public String getHeader(String name) {
            Header header = response.getFirstHeader(name);
            return header != null ? header.getValue() : null;
        }

        @Override
        public InputStream getBody() {
            try {
                return response.getEntity() != null ? response.getEntity().getContent() : EmptyInputStream.INSTANCE;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            try {
                request.abort();
            } catch (Exception e) {
                LOGGER.debug("Failed to abort the request", e);
            }
            try {
                response.close();
            } catch (ConnectionClosedException e) {
                LOGGER.trace("Failed to close the response", e);
            } catch (Exception e) {
                LOGGER.debug("Failed to close the response", e);
            }
        }
    }

    /** Sends the request, expects {@code 101 Switching Protocols}, then pumps the caller's stdin into the socket. */
    private static final class HijackingRequestExecutor extends HttpRequestExecutor {

        HijackingRequestExecutor() {
            super(null);
        }

        @Override
        public ClassicHttpResponse execute(ClassicHttpRequest request, HttpClientConnection connection,
                                           HttpResponseInformationCallback callback, HttpContext context)
                throws IOException, HttpException {
            InputStream hijacked = (InputStream) context.getAttribute(HIJACKED_INPUT);
            if (hijacked == null) {
                return super.execute(request, connection, callback, context);
            }
            return executeHijacked(request, connection, (HttpCoreContext) context, hijacked);
        }

        private ClassicHttpResponse executeHijacked(ClassicHttpRequest request, HttpClientConnection connection,
                                                    HttpCoreContext context, InputStream hijacked)
                throws HttpException, IOException {
            try {
                context.setSSLSession(connection.getSSLSession());
                context.setEndpointDetails(connection.getEndpointDetails());
                if (request.getVersion() != null) {
                    context.setProtocolVersion(request.getVersion());
                }
                connection.sendRequestHeader(request);
                connection.sendRequestEntity(request);
                connection.flush();
                ClassicHttpResponse response = connection.receiveResponseHeader();
                if (response.getCode() != 101) {
                    connection.terminateRequest(request);
                    throw new ProtocolException("Expected 101 Switching Protocols, got: "
                            + new org.apache.hc.core5.http.message.StatusLine(response));
                }
                Thread pump = new Thread(() -> {
                    BasicClassicHttpRequest stdin = new BasicClassicHttpRequest("POST", "/");
                    stdin.setHeader("Content-Length", Long.MAX_VALUE);
                    stdin.setEntity(new HijackedEntity(hijacked));
                    try {
                        connection.sendRequestEntity(stdin);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                pump.setName("shipir-docker-hijacking-stream-" + System.identityHashCode(request));
                pump.setDaemon(true);
                pump.start();
                response.setCode(200);
                connection.receiveResponseEntity(response);
                return response;
            } catch (HttpException | IOException | RuntimeException e) {
                org.apache.hc.core5.io.Closer.closeQuietly(connection);
                throw e;
            }
        }
    }

    private static final class HijackedEntity extends AbstractHttpEntity {

        private final InputStream in;

        HijackedEntity(InputStream in) {
            super((String) null, null, false);
            this.in = in;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        }

        @Override
        public InputStream getContent() {
            return in;
        }

        @Override
        public boolean isStreaming() {
            return true;
        }

        @Override
        public boolean isRepeatable() {
            return false;
        }

        @Override
        public long getContentLength() {
            return Long.MAX_VALUE;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
