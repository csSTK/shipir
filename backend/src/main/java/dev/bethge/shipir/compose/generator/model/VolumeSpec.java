package dev.bethge.shipir.compose.generator.model;

/** Top-level named volume; {@code external} volumes must already exist and get no driver. */
public record VolumeSpec(String name, String driver, boolean external) {
}
