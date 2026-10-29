package dev.bethge.shipir.compose.template;

import dev.bethge.shipir.compose.generator.model.ComposeSpec;

import java.util.List;

public record AppTemplate(String id, String name, String description, List<EnvHint> env, ComposeSpec spec) {

    /**
     * Input the user is asked for when installing a template. {@code defaultValue} prefills a non-secret hint;
     * {@code generate} asks the client to fill a secret hint with a random value. Only one of them may be set,
     * and only for the matching kind (checked in {@link TemplateCatalog}).
     */
    public record EnvHint(String key, String description, boolean secret, String defaultValue, Boolean generate) {

        public EnvHint {
            generate = Boolean.TRUE.equals(generate);
        }
    }
}
