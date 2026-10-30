package dev.bethge.shipir.proxy.dto;

import java.util.List;

/**
 * A container that can be published. {@code ports} are its exposed TCP ports (ascending); {@code suggestedPort} is
 * set only when exactly one exists; {@code published} when a route already targets the container.
 */
public record ProxyRouteCandidate(String containerName, String image, String state, List<Integer> ports,
                                  Integer suggestedPort, boolean published) {
}
