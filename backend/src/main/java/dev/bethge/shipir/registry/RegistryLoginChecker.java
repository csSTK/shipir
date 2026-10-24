package dev.bethge.shipir.registry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Checks a login against the Registry v2 API: {@code GET /v2/} with Basic auth, following a Bearer token
 * challenge (realm + service) when the registry delegates authentication. Never follows redirects.
 */
@Component
@EnableConfigurationProperties(RegistryProperties.class)
class RegistryLoginChecker {

    static final String OK = "OK";
    static final String AUTH_FAILED = "AUTH_FAILED";
    static final String UNREACHABLE = "UNREACHABLE";

    private final RegistryProperties properties;
    private final HttpClient client;

    RegistryLoginChecker(RegistryProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** @param registryHost normalised host (see {@link RegistryHost}) */
    String check(String registryHost, String username, String secret) {
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
        String scheme = properties.allowInsecureHttp() ? "http" : "https";
        try {
            HttpResponse<Void> first = get(URI.create(scheme + "://" + RegistryHost.apiHost(registryHost) + "/v2/"),
                    basic);
            int status = first.statusCode();
            if (status == 200) {
                return OK;
            }
            if (status != 401) {
                return status == 403 ? AUTH_FAILED : UNREACHABLE;
            }
            String challenge = first.headers().firstValue("WWW-Authenticate").orElse("");
            if (!challenge.regionMatches(true, 0, "Bearer", 0, 6)) {
                return AUTH_FAILED;
            }
            return checkBearer(challenge, basic);
        } catch (IOException | IllegalArgumentException e) {
            return UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UNREACHABLE;
        }
    }

    private String checkBearer(String challenge, String basic) throws IOException, InterruptedException {
        BearerChallenge parsed = BearerChallenge.parse(challenge);
        URI tokenUri = parsed == null ? null : parsed.tokenUri(properties.allowInsecureHttp(), null);
        if (tokenUri == null) {
            return UNREACHABLE;
        }
        int status = get(tokenUri, basic).statusCode();
        if (status == 200) {
            return OK;
        }
        return status == 401 || status == 403 ? AUTH_FAILED : UNREACHABLE;
    }

    private HttpResponse<Void> get(URI uri, String authorization) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.timeout())
                .header("Authorization", authorization)
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.discarding());
    }
}
