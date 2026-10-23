package dev.bethge.shipir.ai.settings;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Probes {@code GET {baseUrl}/models} with the key. No redirects; the response body is never read. */
@Component
class AiConnectionChecker {

    static final String OK = "OK";
    static final String AUTH_FAILED = "AUTH_FAILED";
    static final String UNREACHABLE = "UNREACHABLE";

    private final AiSettingsProperties properties;

    AiConnectionChecker(AiSettingsProperties properties) {
        this.properties = properties;
    }

    String check(String baseUrl, String apiKey) {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.testTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(modelsUrl(baseUrl)))
                    .timeout(properties.testTimeout())
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .GET().build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 401 || status == 403) {
                return AUTH_FAILED;
            }
            return status >= 200 && status < 300 ? OK : UNREACHABLE;
        } catch (IOException | IllegalArgumentException e) {
            return UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UNREACHABLE;
        }
    }

    private static String modelsUrl(String baseUrl) {
        String b = baseUrl.trim();
        return (b.endsWith("/") ? b : b + "/") + "models";
    }
}
