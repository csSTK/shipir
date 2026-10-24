package dev.bethge.shipir.host.dto;

/** Create a named volume; {@code driver} may only be {@code local} (default). */
public record CreateVolumeRequest(String name, String driver) {
}
