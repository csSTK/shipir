package dev.bethge.shipir.host.dto;

import java.util.UUID;

/**
 * One hit of the global search. {@code type} is HOST, DEPLOYMENT or CONTAINER; {@code id} is the host id, the
 * managed deployment id (null for unmanaged Compose projects) or the container id; {@code name} is the display name
 * (container name, project name, host name); {@code detail} is the image (container) or status (deployment).
 */
public record SearchResult(Type type, String id, String name, UUID hostId, String hostName, boolean managed,
                           String detail) {

    public enum Type { HOST, DEPLOYMENT, CONTAINER }
}
