package dev.bethge.shipir.registry;

import dev.bethge.shipir.common.net.EgressHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * Checks a login against the Registry v2 API: {@code GET /v2/} with Basic auth, following a Bearer token
 * challenge (realm + service) when the registry delegates authentication. Never follows redirects. Registry and realm host
 * both go through the egress policy (internal targets are refused unless {@code shipir.egress.allow-private-ranges}), so
 * credentials are never sent to a denied host.
 */
@Component
@EnableConfigurationProperties(RegistryProperties.class)
class RegistryLoginChecker {

    static final String OK = "OK";
    static final String AUTH_FAILED = "AUTH_FAILED";
    static final String UNREACHABLE = "UNREACHABLE";

    private final RegistryProperties properties;
    private final EgressHttpClient egress;

    RegistryLoginChecker(RegistryProperties properties, EgressHttpClient egress) {
        this.properties = properties;
        this.egress = egress;
    }

    /** @param registryHost normalised host (see {@link RegistryHost}) */
    String check(String registryHost, String username, String secret) {
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + secret).getBytes(StandardCharsets.UTF_8));
        String scheme = properties.allowInsecureHttp() ? "http" : "https";
        try {
            EgressHttpClient.Response first = get(URI.create(scheme + "://" + RegistryHost.apiHost(registryHost) + "/v2/"),
                    basic);
            int status = first.status();
            if (status == 200) {
                return OK;
            }
            if (status != 401) {
                return status == 403 ? AUTH_FAILED : UNREACHABLE;
            }
            String challenge = Objects.toString(first.header("WWW-Authenticate"), "");
            if (!challenge.regionMatches(true, 0, "Bearer", 0, 6)) {
                return AUTH_FAILED;
            }
            return checkBearer(challenge, basic);
        } catch (IOException | IllegalArgumentException e) {
            return UNREACHABLE;
        }
    }

    private String checkBearer(String challenge, String basic) throws IOException {
        BearerChallenge parsed = BearerChallenge.parse(challenge);
        URI tokenUri = parsed == null ? null : parsed.tokenUri(properties.allowInsecureHttp(), null);
        if (tokenUri == null) {
            return UNREACHABLE;
        }
        int status = get(tokenUri, basic).status();
        if (status == 200) {
            return OK;
        }
        return status == 401 || status == 403 ? AUTH_FAILED : UNREACHABLE;
    }

    private EgressHttpClient.Response get(URI uri, String authorization) throws IOException {
        return egress.send("GET", uri, Map.of("Authorization", authorization), properties.timeout());
    }
}
