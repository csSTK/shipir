package dev.bethge.shipir.compose.generator.model;

/** Either a literal {@code value} or a reference {@code ref} to a key in the deployment's .env file (written as ${REF}). */
public record EnvVarSpec(String key, String value, String ref) {
}
