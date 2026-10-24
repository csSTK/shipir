package dev.bethge.shipir.docker.dto;

/** {@code driver} and {@code subnet} (CIDR) may be null for the daemon defaults. */
public record NetworkCreateSpec(String name, String driver, boolean internal, String subnet) {
}
