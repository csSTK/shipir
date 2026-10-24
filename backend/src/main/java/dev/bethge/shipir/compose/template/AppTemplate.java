package dev.bethge.shipir.compose.template;

import dev.bethge.shipir.compose.generator.model.ComposeSpec;

import java.util.List;

public record AppTemplate(String id, String name, String description, String logo, List<EnvHint> env, ComposeSpec spec) {

    public record EnvHint(String key, String description, boolean secret) {
    }
}
