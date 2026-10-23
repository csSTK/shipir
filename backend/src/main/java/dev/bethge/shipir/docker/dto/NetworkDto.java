package dev.bethge.shipir.docker.dto;

/** One network. {@code subnet} is the first IPAM subnet or null; {@code containerCount} counts attached containers (any state). */
public record NetworkDto(String id, String name, String driver, String scope, String subnet, boolean internal,
                         int containerCount) {
}
