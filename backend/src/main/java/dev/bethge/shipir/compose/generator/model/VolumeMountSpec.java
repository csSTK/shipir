package dev.bethge.shipir.compose.generator.model;

/**
 * {@code source} is a declared named volume (plain name) or a bind path relative to the stack directory
 * (contains a slash or starts with a dot, e.g. {@code ./data}). Absolute and traversing paths are rejected.
 */
public record VolumeMountSpec(String source, String target, boolean readOnly) {
}
