package dev.bethge.shipir.registry;

import dev.bethge.shipir.host.ImageReference;
import dev.bethge.shipir.host.RemoteDigestLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the digest a registry serves for a tag: {@code HEAD /v2/<repo>/manifests/<tag>} and the
 * {@code Docker-Content-Digest} header, following a Bearer challenge (pull scope) with the stored login of that
 * registry if there is one, anonymously otherwise. https only, no redirects, every failure is an empty result.
 */
@Component
@EnableConfigurationProperties(RegistryProperties.class)
class RemoteDigestChecker implements RemoteDigestLookup {

    private static final Logger log = LoggerFactory.getLogger(RemoteDigestChecker.class);
    private static final Pattern DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final String ACCEPT = String.join(", ",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    private final RegistryProperties properties;
    private final RegistryCredentialService credentials;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    RemoteDigestChecker(RegistryProperties properties, RegistryCredentialService credentials) {
        this.properties = properties;
        this.credentials = credentials;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Optional<String> digestOf(ImageReference reference) {
        try {
            String basic = basicFor(reference.registryHost());
            String scheme = properties.allowInsecureHttp() ? "http" : "https";
            URI manifest = URI.create(scheme + "://" + RegistryHost.apiHost(reference.registryHost()) + "/v2/"
                    + reference.repository() + "/manifests/" + reference.tag());
            HttpResponse<Void> response = head(manifest, null);
            if (response.statusCode() == 401) {
                BearerChallenge challenge = BearerChallenge.parse(
                        response.headers().firstValue("WWW-Authenticate").orElse(""));
                String token = challenge == null ? null : fetchToken(challenge, reference.repository(), basic);
                if (token == null) {
                    // some registries take Basic directly
                    response = basic == null ? response : head(manifest, basic);
                } else {
                    response = head(manifest, "Bearer " + token);
                }
            }
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return response.headers().firstValue("Docker-Content-Digest").filter(d -> DIGEST.matcher(d).matches());
        } catch (IOException | RuntimeException e) {
            log.debug("Remote digest lookup failed for {}: {}", reference.key(), e.getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private String basicFor(String registryHost) {
        try {
            List<RegistryAuth> all = credentials.decryptedCredentials();
            return all.stream().filter(a -> a.registryHost().equals(registryHost)).findFirst()
                    .map(a -> "Basic " + Base64.getEncoder()
                            .encodeToString((a.username() + ":" + a.secret()).getBytes(StandardCharsets.UTF_8)))
                    .orElse(null);
        } catch (RuntimeException e) {
            return null; // no key or undecryptable: anonymous
        }
    }

    private String fetchToken(BearerChallenge challenge, String repository, String basic)
            throws IOException, InterruptedException {
        URI uri = challenge.tokenUri(properties.allowInsecureHttp(), "repository:" + repository + ":pull");
        if (uri == null) {
            return null;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(properties.timeout()).GET();
        if (basic != null) {
            request.header("Authorization", basic);
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode body = json.readTree(response.body());
        JsonNode token = body.has("token") ? body.get("token") : body.get("access_token");
        return token == null || token.isNull() ? null : token.asString();
    }

    private HttpResponse<Void> head(URI uri, String authorization) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(properties.timeout())
                .header("Accept", ACCEPT).method("HEAD", HttpRequest.BodyPublishers.noBody());
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding());
    }
}
