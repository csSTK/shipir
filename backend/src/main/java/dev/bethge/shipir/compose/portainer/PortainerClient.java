package dev.bethge.shipir.compose.portainer;

import dev.bethge.shipir.common.net.EgressHttpClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Read-only Portainer API access. Goes through {@link EgressHttpClient}: TLS verified, address policy applied inside the
 * DNS lookup the connection uses, no redirects (a 3xx counts as a failure), body capped. The token is only sent as
 * {@code X-API-Key}; errors carry fixed messages, never the token, the URL or any upstream body.
 */
@Component
class PortainerClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final EgressHttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    PortainerClient(EgressHttpClient http) {
        this.http = http;
    }

    /** GET {@code base + path} and parse the JSON answer. A 404 means the endpoint is missing (wrong URL, not Portainer). */
    JsonNode get(URI base, String apiToken, String path) {
        return send(base, apiToken, path, unreachable());
    }

    /** Like {@link #get}, for a single stack lookup: a 404 means Portainer does not know the stack. */
    JsonNode getStack(URI base, String apiToken, String path) {
        return send(base, apiToken, path, new PortainerException(HttpStatus.NOT_FOUND, "PORTAINER_STACK_NOT_FOUND",
                "Portainer does not know this stack"));
    }

    private JsonNode send(URI base, String apiToken, String path, PortainerException notFound) {
        URI uri = URI.create(base.toString() + path);
        EgressHttpClient.Response response;
        try {
            response = http.send("GET", uri, Map.of("X-API-Key", apiToken, "Accept", "application/json"), TIMEOUT);
        } catch (IOException | RuntimeException e) {
            throw isTlsFailure(e) ? tlsFailed() : unreachable();
        }
        int status = response.status();
        if (status == 401 || status == 403) {
            throw new PortainerException(HttpStatus.UNPROCESSABLE_ENTITY, "PORTAINER_AUTH_FAILED",
                    "Portainer rejected the API token");
        }
        if (status == 404) {
            throw notFound;
        }
        if (status < 200 || status >= 300) {
            throw unreachable();
        }
        if (response.truncated()) {
            throw new PortainerException(HttpStatus.BAD_GATEWAY, "PORTAINER_RESPONSE_TOO_LARGE",
                    "Portainer's answer is larger than the 1 MiB import limit");
        }
        try {
            return json.readTree(response.body());
        } catch (RuntimeException e) {
            throw badResponse();
        }
    }

    private static boolean isTlsFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SSLException) {
                return true;
            }
        }
        return false;
    }

    private static PortainerException tlsFailed() {
        return new PortainerException(HttpStatus.BAD_GATEWAY, "PORTAINER_TLS_FAILED",
                "Portainer's TLS certificate is not trusted (self-signed or unknown CA)");
    }

    private static PortainerException unreachable() {
        return new PortainerException(HttpStatus.BAD_GATEWAY, "PORTAINER_UNREACHABLE", "Portainer could not be reached");
    }

    private static PortainerException badResponse() {
        return new PortainerException(HttpStatus.BAD_GATEWAY, "PORTAINER_BAD_RESPONSE",
                "Portainer sent an unusable response");
    }
}
