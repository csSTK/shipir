package dev.bethge.shipir.compose.template;

import dev.bethge.shipir.compose.generator.ComposeGeneratorService;
import dev.bethge.shipir.compose.generator.InvalidComposeSpecException;
import dev.bethge.shipir.compose.generator.model.ServiceSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The bundled app templates, read once at startup. Every template's Compose spec is run through
 * {@link ComposeGeneratorService} here, so a bad bundled file stops the application before it serves anything.
 * The tests load the same file, so a broken template fails the build first.
 */
@Component
public class TemplateCatalog {

    private static final String SOURCE = "templates/apps.json";
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final List<AppTemplate> templates;

    TemplateCatalog(ComposeGeneratorService generator, @Value("classpath:" + SOURCE) Resource resource) {
        this.templates = List.copyOf(load(generator, resource));
    }

    public List<AppTemplate> all() {
        return templates;
    }

    private static List<AppTemplate> load(ComposeGeneratorService generator, Resource resource) {
        List<AppTemplate> parsed;
        try (InputStream in = resource.getInputStream()) {
            parsed = JSON.readValue(in, new TypeReference<List<AppTemplate>>() {
            });
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read " + SOURCE + ": " + e.getMessage(), e);
        }
        Set<String> ids = new HashSet<>();
        for (AppTemplate t : parsed) {
            validate(generator, t, ids);
        }
        return parsed;
    }

    private static void validate(ComposeGeneratorService generator, AppTemplate t, Set<String> ids) {
        if (t == null || t.id() == null || !ID.matcher(t.id()).matches()) {
            throw new IllegalStateException(SOURCE + ": invalid id " + (t == null ? "null" : "\"" + t.id() + "\""));
        }
        if (!ids.add(t.id())) {
            throw new IllegalStateException(SOURCE + ": duplicate id \"" + t.id() + "\"");
        }
        if (blank(t.name()) || blank(t.description())) {
            throw new IllegalStateException(SOURCE + ": template \"" + t.id() + "\" needs name and description");
        }
        validateEnvHints(t);
        try {
            generator.generate(t.spec());
        } catch (InvalidComposeSpecException e) {
            throw new IllegalStateException(SOURCE + ": template \"" + t.id() + "\" is invalid: "
                    + String.join("; ", e.violations()), e);
        }
    }

    private static void validateEnvHints(AppTemplate t) {
        if (t.env() == null) {
            return;
        }
        Set<String> referenced = new HashSet<>();
        if (t.spec() != null && t.spec().services() != null) {
            for (ServiceSpec s : t.spec().services()) {
                if (s.environment() != null) {
                    s.environment().forEach(e -> {
                        if (e.ref() != null) {
                            referenced.add(e.ref());
                        }
                    });
                }
            }
        }
        for (AppTemplate.EnvHint h : t.env()) {
            String where = SOURCE + ": template \"" + t.id() + "\" env hint \"" + h.key() + "\" ";
            if (h.generate() && !h.secret()) {
                throw new IllegalStateException(where + "sets generate but is not secret");
            }
            if (h.defaultValue() != null && h.secret()) {
                throw new IllegalStateException(where + "is secret and must not have a defaultValue");
            }
            if (h.generate() && h.defaultValue() != null) {
                throw new IllegalStateException(where + "cannot have both generate and defaultValue");
            }
            if (h.defaultValue() != null && !referenced.contains(h.key())) {
                throw new IllegalStateException(where + "has a defaultValue but no service references it");
            }
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
