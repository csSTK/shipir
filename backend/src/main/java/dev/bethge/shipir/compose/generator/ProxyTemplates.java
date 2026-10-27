package dev.bethge.shipir.compose.generator;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Bundled Compose files that set up a reverse proxy. The names are a fixed allow list (never a path built from
 * input). The Traefik file defines the {@code websecure} entrypoint, the {@code letsencrypt} resolver and the
 * {@code proxy} network that the generated Traefik labels refer to.
 */
@Component
class ProxyTemplates {

    private static final Map<String, String> RESOURCES = Map.of("traefik", "templates/proxy/traefik.yaml");

    /** @throws IllegalArgumentException for a proxy without a bundled template */
    String yaml(String proxy) {
        String resource = RESOURCES.get(proxy);
        if (resource == null) {
            throw new IllegalArgumentException("No bundled template for this proxy");
        }
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
