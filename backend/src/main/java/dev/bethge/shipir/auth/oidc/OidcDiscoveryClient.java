package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.common.net.EgressHttpClient;
import dev.bethge.shipir.common.net.EgressPolicy;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Fetches and checks {@code {issuer}/.well-known/openid-configuration}: no redirects, bounded time, the document's
 * {@code issuer} must equal the configured one, and every endpoint must be https (unless tests allow http).
 * All traffic goes through the shared egress policy ({@code common/net}): internal targets, also those named by the
 * discovery document, fail like an unreachable provider.
 */
@Component
class OidcDiscoveryClient {

    record Metadata(String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri) {
    }

    /** Same cap as {@link EgressHttpClient}: no OIDC document (token response, JWKS) is legitimately larger. */
    static final long MAX_BODY_BYTES = 1_048_576;

    private static final Map<String, String> ACCEPT_JSON = Map.of("Accept", "application/json");

    private final OidcProperties properties;
    private final JsonMapper mapper;
    private final EgressPolicy egressPolicy;
    private final EgressHttpClient egressClient;
    private final ClientHttpRequestFactory requestFactory;

    OidcDiscoveryClient(OidcProperties properties, JsonMapper mapper, EgressPolicy egressPolicy,
                        EgressHttpClient egressClient) {
        this.properties = properties;
        this.mapper = mapper;
        this.egressPolicy = egressPolicy;
        this.egressClient = egressClient;
        Timeout timeout = Timeout.ofMilliseconds(properties.timeout().toMillis());
        HttpComponentsClientHttpRequestFactory apache = new HttpComponentsClientHttpRequestFactory(HttpClientBuilder.create()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(egressPolicy.dnsResolver())
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(timeout).setSocketTimeout(timeout).build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().build());
        this.requestFactory = new InterceptingClientHttpRequestFactory(apache,
                List.of((request, body, execution) -> capped(execution.execute(request, body))));
    }

    /**
     * Spring's RestClient/RestTemplate and the Nimbus JWKS fetch buffer whatever the server sends, so the cap sits
     * in the response: a declared Content-Length over the limit is refused at once, and the stream fails after the
     * limit when no length is declared (chunked). The caller sees an IOException, like any broken response.
     */
    private static ClientHttpResponse capped(ClientHttpResponse response) throws IOException {
        if (response.getHeaders().getContentLength() > MAX_BODY_BYTES) {
            response.close();
            throw new IOException("OIDC response exceeds " + MAX_BODY_BYTES + " bytes");
        }
        return new ClientHttpResponse() {
            @Override
            public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
                return response.getStatusCode();
            }

            @Override
            public String getStatusText() throws IOException {
                return response.getStatusText();
            }

            @Override
            public void close() {
                response.close();
            }

            @Override
            public InputStream getBody() throws IOException {
                return new FilterInputStream(response.getBody()) {
                    private long read;

                    @Override
                    public int read() throws IOException {
                        int b = super.read();
                        count(b < 0 ? 0 : 1);
                        return b;
                    }

                    @Override
                    public int read(byte[] buf, int off, int len) throws IOException {
                        int n = super.read(buf, off, len);
                        count(Math.max(n, 0));
                        return n;
                    }

                    private void count(int n) throws IOException {
                        read += n;
                        if (read > MAX_BODY_BYTES) {
                            throw new IOException("OIDC response exceeds " + MAX_BODY_BYTES + " bytes");
                        }
                    }
                };
            }

            @Override
            public HttpHeaders getHeaders() {
                return response.getHeaders();
            }
        };
    }

    Metadata fetch(String issuer) {
        String url = (issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer)
                + "/.well-known/openid-configuration";
        try {
            EgressHttpClient.Response response = egressClient.send("GET", URI.create(url), ACCEPT_JSON,
                    properties.timeout());
            if (response.status() != 200) {
                throw unreachable();
            }
            JsonNode doc = mapper.readTree(response.body());
            Metadata metadata = new Metadata(text(doc, "issuer"), text(doc, "authorization_endpoint"),
                    text(doc, "token_endpoint"), text(doc, "jwks_uri"));
            if (!issuer.equals(metadata.issuer()) || !allowed(metadata.authorizationEndpoint())
                    || !allowed(metadata.tokenEndpoint()) || !allowed(metadata.jwksUri())) {
                throw new OidcException("PROVIDER_INVALID", "The provider's discovery document is not usable");
            }
            // The server calls these two itself. A denied target looks exactly like an unreachable provider.
            egressPolicy.check(URI.create(metadata.tokenEndpoint()));
            egressPolicy.check(URI.create(metadata.jwksUri()));
            return metadata;
        } catch (IOException | IllegalArgumentException | tools.jackson.core.JacksonException e) {
            throw unreachable();
        }
    }

    /** Request factory for the token exchange and the JWKS fetch: egress-checked, no redirects, bounded time. */
    ClientHttpRequestFactory requestFactory() {
        return requestFactory;
    }

    private boolean allowed(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        try {
            URI uri = new URI(endpoint);
            return uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme())
                    || (properties.allowInsecureHttp() && "http".equalsIgnoreCase(uri.getScheme())));
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    private static String text(JsonNode doc, String field) {
        JsonNode n = doc.get(field);
        return n != null && n.isString() ? n.asString() : null;
    }

    private static OidcException unreachable() {
        return new OidcException("PROVIDER_UNREACHABLE", "The OIDC provider could not be reached");
    }
}
