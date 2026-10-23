package dev.bethge.shipir.compose.generator.model;

/** Top-level network; {@code external} networks must already exist and get no driver. */
public record NetworkSpec(String name, String driver, boolean external) {
}
