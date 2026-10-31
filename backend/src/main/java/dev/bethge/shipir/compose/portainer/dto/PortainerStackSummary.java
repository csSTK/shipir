package dev.bethge.shipir.compose.portainer.dto;

/** One Portainer stack; {@code supported} is true only for standalone Compose stacks, otherwise {@code reason} says why not. */
public record PortainerStackSummary(long id, String name, String type, long endpointId, boolean supported, String reason) {
}
