package dev.bethge.shipir.host;

import java.util.List;
import java.util.UUID;

/**
 * Port implemented by proxy/: removes the managed reverse proxy of a host from the Docker host when the environment is
 * deleted. {@code host/} must not import {@code proxy/}, so the call goes through this interface.
 */
public interface ManagedProxyRemoval {

    /**
     * Best effort and never throws for a Docker or Compose failure: stops the proxy project, deletes its volumes and
     * the proxy network if no container uses it. Returns what is still left on the Docker host, one readable entry per
     * item ({@code project shipir-proxy}, {@code volume <name>}, {@code network shipir-proxy}); empty when the host has
     * no managed proxy or everything was removed.
     */
    List<String> removeBestEffort(UUID hostId);
}
