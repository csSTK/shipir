package dev.bethge.shipir.compose.generator.model;

/** {@code host:container/protocol}; {@code host} null publishes on a random host port; protocol tcp (default) or udp. */
public record PortSpec(Integer host, Integer container, String protocol) {
}
