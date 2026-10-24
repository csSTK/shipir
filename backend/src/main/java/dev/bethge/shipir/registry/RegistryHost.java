package dev.bethge.shipir.registry;

import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Validation and normalisation of a registry host: {@code hostname[:port]}, lower case, no scheme or path. */
final class RegistryHost {

    /** Canonical key for Docker Hub. */
    static final String DOCKER_HUB = "docker.io";
    private static final Set<String> DOCKER_HUB_ALIASES =
            Set.of("docker.io", "index.docker.io", "registry-1.docker.io", "registry.hub.docker.com");
    private static final Pattern HOST = Pattern.compile(
            "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*(:[0-9]{1,5})?");

    private RegistryHost() {
    }

    static String normalize(String raw) {
        String host = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (host.length() > 255 || !HOST.matcher(host).matches() || !portInRange(host)) {
            throw new RegistryException(HttpStatus.BAD_REQUEST, "INVALID_REGISTRY_HOST",
                    "The registry host must be hostname[:port] without scheme or path");
        }
        return DOCKER_HUB_ALIASES.contains(host) ? DOCKER_HUB : host;
    }

    /** Host part that serves the Registry v2 API. */
    static String apiHost(String normalizedHost) {
        return DOCKER_HUB.equals(normalizedHost) ? "registry-1.docker.io" : normalizedHost;
    }

    private static boolean portInRange(String host) {
        int colon = host.indexOf(':');
        if (colon < 0) {
            return true;
        }
        int port = Integer.parseInt(host.substring(colon + 1));
        return port >= 1 && port <= 65535;
    }
}
