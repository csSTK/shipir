package dev.bethge.shipir.ai.settings;

import dev.bethge.shipir.common.net.EgressHttpClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Probes {@code GET {baseUrl}/models} with the key (Bearer, or {@code x-goog-api-key} for Gemini). No redirects; the
 * response body is ignored. Goes through the egress policy: an internal base URL is refused before any connection, so the
 * key is never sent there, and the result is the same {@code UNREACHABLE} as for a host that is down.
 */
@Component
class AiConnectionChecker {

    static final String OK = "OK";
    static final String AUTH_FAILED = "AUTH_FAILED";
    static final String UNREACHABLE = "UNREACHABLE";

    private final AiSettingsProperties properties;
    private final EgressHttpClient egress;

    AiConnectionChecker(AiSettingsProperties properties, EgressHttpClient egress) {
        this.properties = properties;
        this.egress = egress;
    }

    String check(AiProvider provider, String baseUrl, String apiKey) {
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            if (provider == AiProvider.GEMINI) {
                headers.put("x-goog-api-key", apiKey);
            } else {
                headers.put("Authorization", "Bearer " + apiKey);
            }
            int status = egress.send("GET", URI.create(modelsUrl(baseUrl)), headers, properties.testTimeout()).status();
            if (status == 401 || status == 403) {
                return AUTH_FAILED;
            }
            return status >= 200 && status < 300 ? OK : UNREACHABLE;
        } catch (IOException | RuntimeException e) {
            return UNREACHABLE;
        }
    }

    private static String modelsUrl(String baseUrl) {
        String b = baseUrl.trim();
        return (b.endsWith("/") ? b : b + "/") + "models";
    }
}
