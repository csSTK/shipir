package dev.bethge.shipir.proxy;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Bundled Compose files for the managed reverse proxies (Traefik, Caddy). The resource names are a fixed map keyed by
 * {@link ProxyType}, never a path built from input. Both files join the external {@code shipir-proxy} network and keep
 * their configuration and certificate data in named volumes; neither mounts the Docker socket.
 */
@Component
public class ManagedProxyTemplates {

    private static final Map<ProxyType, String> RESOURCES = Map.of(
            ProxyType.TRAEFIK, "templates/proxy/managed-traefik.yaml",
            ProxyType.CADDY, "templates/proxy/managed-caddy.yaml");

    /** @throws IllegalArgumentException if the proxy type has no managed template */
    public String yaml(ProxyType type) {
        String resource = RESOURCES.get(Objects.requireNonNull(type, "type"));
        if (resource == null) {
            throw new IllegalArgumentException("No managed template for " + type);
        }
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
