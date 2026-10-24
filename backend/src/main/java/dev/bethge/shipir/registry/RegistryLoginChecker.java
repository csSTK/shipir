package dev.bethge.shipir.registry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern CHALLENGE_PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");

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
        Map<String, String> params = new LinkedHashMap<>();
        Matcher m = CHALLENGE_PARAM.matcher(challenge);
        while (m.find()) {
            params.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        }
        String realm = params.get("realm");
        if (realm == null) {
            return UNREACHABLE;
        }
        URI realmUri = URI.create(realm);
        String scheme = realmUri.getScheme();
        boolean schemeOk = "https".equalsIgnoreCase(scheme)
                || (properties.allowInsecureHttp() && "http".equalsIgnoreCase(scheme));
        if (!schemeOk || realmUri.getHost() == null || realmUri.getUserInfo() != null) {
            return UNREACHABLE;
        }
        String service = params.get("service");
        String query = realmUri.getRawQuery();
        String extra = service == null ? "" : "service=" + URLEncoder.encode(service, StandardCharsets.UTF_8);
        String joined = query == null ? extra : (extra.isEmpty() ? query : query + "&" + extra);
        URI tokenUri = URI.create(realm.replaceFirst("\\?.*$", "") + (joined.isEmpty() ? "" : "?" + joined));
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
