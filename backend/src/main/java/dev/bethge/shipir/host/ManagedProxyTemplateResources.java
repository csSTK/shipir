package dev.bethge.shipir.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Images and networks the bundled managed-proxy Compose templates declare ({@code templates/proxy/managed-*.yaml}).
 * Lets {@link SystemManagedGuard} protect them while the proxy is stopped and has no containers to learn them from.
 * Every {@code managed-*.yaml} on the classpath is picked up, so a new template is protected without code changes.
 * Read-only, nothing here depends on the {@code proxy/} package.
 */
final class ManagedProxyTemplateResources {

    private static final Logger log = LoggerFactory.getLogger(ManagedProxyTemplateResources.class);

    private static final String TEMPLATE_PATTERN = "classpath*:templates/proxy/managed-*.yaml";

    /** Loaded on first use, not in a static initializer: a broken template must not poison the class. */
    private static volatile Resources bundled;

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
            // Only the exception type: SnakeYAML messages quote input, which can hold values from .env.
            log.warn("Compose file is not valid YAML, no images or networks read from it ({})",
                    e.getClass().getSimpleName());
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

    /** The union over all bundled managed-proxy templates, loaded once. */
    static Resources bundled() {
        Resources r = bundled;
        if (r == null) {
            r = load(TEMPLATE_PATTERN);
            bundled = r;
        }
        return r;
    }

    /**
     * Union of the resources of every template matching the pattern.
     * Fails with an {@link IllegalStateException} when nothing matches, so a packaging mistake surfaces at first use.
     */
    static Resources load(String locationPattern) {
        Resource[] templates;
        try {
            templates = new PathMatchingResourcePatternResolver().getResources(locationPattern);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list managed proxy templates " + locationPattern, e);
        }
        if (templates.length == 0) {
            throw new IllegalStateException("No managed proxy templates found for " + locationPattern);
        }
        Set<String> images = new HashSet<>();
        Set<String> networks = new HashSet<>();
        for (Resource template : templates) {
            Resources r = parse(read(template));
            images.addAll(r.images());
            networks.addAll(r.networks());
        }
        return new Resources(Set.copyOf(images), Set.copyOf(networks));
    }

    private static String read(Resource template) {
        try (InputStream in = template.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read managed proxy template " + template.getFilename(), e);
        }
    }

    private ManagedProxyTemplateResources() {
    }

    static Set<String> images() {
        return bundled().images();
    }

    static Set<String> networks() {
        return bundled().networks();
    }
}
