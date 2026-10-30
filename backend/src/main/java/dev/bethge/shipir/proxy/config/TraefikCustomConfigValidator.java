package dev.bethge.shipir.proxy.config;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.render.RenderedFile;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Traefik reads the custom file from its dynamic (file provider) directory. Only the dynamic sections are allowed: the
 * static configuration (entry points, providers, API, ...) lives in the Traefik command line and must not be changed
 * from a file that is merged at runtime. The content must be one YAML mapping without duplicate keys or aliases.
 * Traefik evaluates Go templates ({@code {{ ... }}}) in dynamic files (it can read environment variables with them), so
 * the delimiter is rejected altogether.
 */
@Component
class TraefikCustomConfigValidator implements CustomConfigValidator {

    static final Set<String> ALLOWED_KEYS = Set.of("http", "tcp", "udp", "tls");
    private static final int MAX_MESSAGE = 300;
    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    @Override
    public ProxyType type() {
        return ProxyType.TRAEFIK;
    }

    @Override
    public void validate(UUID hostId, String content, List<RenderedFile> managed) {
        CustomConfigValidator.checkSize(content);
        if (content.contains("{{")) {
            throw CustomConfigValidator.invalid("Go templates (\"{{\") are not allowed, Traefik would evaluate them");
        }
        if (content.isBlank()) {
            return;
        }
        rejectAliases(content);
        JsonNode root;
        try {
            root = YAML.readTree(content);
        } catch (RuntimeException e) {
            throw CustomConfigValidator.invalid("The custom configuration is not valid YAML: " + firstLine(e.getMessage()));
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return; // only comments
        }
        if (!root.isObject()) {
            throw CustomConfigValidator.invalid("The custom configuration must be a YAML mapping with the keys "
                    + "http, tcp, udp or tls");
        }
        for (String key : root.propertyNames()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw CustomConfigValidator.invalid("The top-level key \"" + key + "\" is not allowed here, only http, tcp, "
                        + "udp and tls (dynamic configuration) are");
            }
        }
    }

    /** Aliases allow exponential expansion ("billion laughs"); a proxy file has no use for them. */
    private static void rejectAliases(String content) {
        try {
            for (Event event : new Parse(LoadSettings.builder().build()).parseString(content)) {
                if (event instanceof AliasEvent) {
                    throw CustomConfigValidator.invalid("YAML aliases (*name) are not allowed in the custom configuration");
                }
            }
        } catch (dev.bethge.shipir.proxy.ProxyException e) {
            throw e;
        } catch (RuntimeException e) {
            throw CustomConfigValidator.invalid("The custom configuration is not valid YAML: " + firstLine(e.getMessage()));
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "syntax error";
        }
        String line = message.lines().findFirst().orElse("syntax error").trim();
        return line.length() <= MAX_MESSAGE ? line : line.substring(0, MAX_MESSAGE) + "...";
    }
}
