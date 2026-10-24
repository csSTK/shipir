package dev.bethge.shipir.auth.oidc;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Fetches and checks {@code {issuer}/.well-known/openid-configuration}: no redirects, bounded time, the document's
 * {@code issuer} must equal the configured one, and every endpoint must be https (unless tests allow http).
 */
@Component
class OidcDiscoveryClient {

    record Metadata(String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri) {
    }

    private final OidcProperties properties;
    private final JsonMapper mapper;

    OidcDiscoveryClient(OidcProperties properties, JsonMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    Metadata fetch(String issuer) {
        String url = (issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer)
                + "/.well-known/openid-configuration";
        try (HttpClient client = httpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(properties.timeout()).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw unreachable();
            }
            JsonNode doc = mapper.readTree(response.body());
            Metadata metadata = new Metadata(text(doc, "issuer"), text(doc, "authorization_endpoint"),
                    text(doc, "token_endpoint"), text(doc, "jwks_uri"));
            if (!issuer.equals(metadata.issuer()) || !allowed(metadata.authorizationEndpoint())
                    || !allowed(metadata.tokenEndpoint()) || !allowed(metadata.jwksUri())) {
                throw new OidcException("PROVIDER_INVALID", "The provider's discovery document is not usable");
            }
            return metadata;
        } catch (IOException | IllegalArgumentException | tools.jackson.core.JacksonException e) {
            throw unreachable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unreachable();
        }
    }

    /** The shared HTTP client settings of all calls to the provider. */
    HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient());
        factory.setReadTimeout(properties.timeout());
        return factory;
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
