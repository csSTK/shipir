package dev.bethge.shipir.registry;

import dev.bethge.shipir.common.net.EgressHttpClient;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the digest a registry serves for a tag: {@code HEAD /v2/<repo>/manifests/<tag>} and the
 * {@code Docker-Content-Digest} header, following a Bearer challenge (pull scope) with the stored login of that
 * registry if there is one, anonymously otherwise. https only, no redirects, every connection checked by the egress policy (internal targets are refused), every failure is an empty result.
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
    private final EgressHttpClient egress;
    private final JsonMapper json = JsonMapper.builder().build();

    RemoteDigestChecker(RegistryProperties properties, RegistryCredentialService credentials,
                        EgressHttpClient egress) {
        this.properties = properties;
        this.credentials = credentials;
        this.egress = egress;
    }

    @Override
    public Optional<String> digestOf(ImageReference reference) {
        try {
            String basic = basicFor(reference.registryHost());
            String scheme = properties.allowInsecureHttp() ? "http" : "https";
            URI manifest = URI.create(scheme + "://" + RegistryHost.apiHost(reference.registryHost()) + "/v2/"
                    + reference.repository() + "/manifests/" + reference.tag());
            EgressHttpClient.Response response = head(manifest, null);
            if (response.status() == 401) {
                BearerChallenge challenge = BearerChallenge.parse(
                        Objects.toString(response.header("WWW-Authenticate"), ""));
                String token = challenge == null ? null : fetchToken(challenge, reference.repository(), basic);
                if (token == null) {
                    // some registries take Basic directly
                    response = basic == null ? response : head(manifest, basic);
                } else {
                    response = head(manifest, "Bearer " + token);
                }
            }
            if (response.status() != 200) {
                return Optional.empty();
            }
            return Optional.ofNullable(response.header("Docker-Content-Digest")).filter(d -> DIGEST.matcher(d).matches());
        } catch (IOException | RuntimeException e) {
            log.debug("Remote digest lookup failed for {}: {}", reference.key(), e.getClass().getSimpleName());
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
            throws IOException {
        URI uri = challenge.tokenUri(properties.allowInsecureHttp(), "repository:" + repository + ":pull");
        if (uri == null) {
            return null;
        }
        EgressHttpClient.Response response = egress.send("GET", uri,
                basic == null ? Map.of() : Map.of("Authorization", basic), properties.timeout());
        if (response.status() != 200) {
            return null;
        }
        JsonNode body = json.readTree(response.body());
        JsonNode token = body.has("token") ? body.get("token") : body.get("access_token");
        return token == null || token.isNull() ? null : token.asString();
    }

    private EgressHttpClient.Response head(URI uri, String authorization) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", ACCEPT);
        if (authorization != null) {
            headers.put("Authorization", authorization);
        }
        return egress.send("HEAD", uri, headers, properties.timeout());
    }
}
