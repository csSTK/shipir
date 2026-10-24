package dev.bethge.shipir.docker.dto;

/** {@code driver} null = local. */
public record VolumeCreateSpec(String name, String driver) {
}
