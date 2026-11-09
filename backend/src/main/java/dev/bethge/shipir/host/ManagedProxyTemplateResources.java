package dev.bethge.shipir.host;

import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Images and networks the bundled managed-proxy Compose templates declare ({@code templates/proxy/managed-*.yaml}).
 * Lets {@link SystemManagedGuard} protect them while the proxy is stopped and has no containers to learn them from.
 * The resource names are fixed; read-only, nothing here depends on the {@code proxy/} package.
 */
final class ManagedProxyTemplateResources {

    private static final List<String> RESOURCES = List.of(
            "templates/proxy/managed-traefik.yaml", "templates/proxy/managed-caddy.yaml");

    private static final Set<String> IMAGES = new HashSet<>();
    private static final Set<String> NETWORKS = new HashSet<>();

    static {
        for (String resource : RESOURCES) {
            try (InputStream in = new ClassPathResource(resource).getInputStream()) {
                Resources r = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                IMAGES.addAll(r.images());
                NETWORKS.addAll(r.networks());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Images and networks (real names) a Compose file declares; empty when it is not parseable. */
    record Resources(Set<String> images, Set<String> networks) {
    }

    static Resources parse(String composeYaml) {
        Set<String> images = new HashSet<>();
        Set<String> networks = new HashSet<>();
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(composeYaml);
        } catch (RuntimeException e) {
            return new Resources(Set.of(), Set.of());
        }
        if (root instanceof Map<?, ?> doc) {
            if (doc.get("services") instanceof Map<?, ?> services) {
                for (Object service : services.values()) {
                    if (service instanceof Map<?, ?> s && s.get("image") instanceof String image) {
                        images.add(image);
                    }
                }
            }
            if (doc.get("networks") instanceof Map<?, ?> nets) {
                for (Map.Entry<?, ?> e : nets.entrySet()) {
                    networks.add(e.getValue() instanceof Map<?, ?> n && n.get("name") instanceof String s
                            ? s : String.valueOf(e.getKey()));
                }
            }
        }
        return new Resources(images, networks);
    }

    private ManagedProxyTemplateResources() {
    }

    static Set<String> images() {
        return Set.copyOf(IMAGES);
    }

    static Set<String> networks() {
        return Set.copyOf(NETWORKS);
    }
}
