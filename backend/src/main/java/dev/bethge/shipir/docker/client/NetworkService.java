package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.CreatedResourceDto;
import dev.bethge.shipir.docker.dto.NetworkCreateSpec;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import dev.bethge.shipir.docker.dto.NetworkDto;

import java.util.List;
import java.util.UUID;

public interface NetworkService {

    /** All networks of the host with usage derived from the container list. Throws {@code DockerConnectionException}. */
    List<NetworkDto> list(UUID hostId, DockerEndpoint endpoint);

    /**
     * Removes a network. Throws {@code DockerConnectionException} with NOT_FOUND, IN_USE (containers attached)
     * or BUILTIN_NETWORK (bridge, host, none; refused without removing).
     */
    void removeNetwork(UUID hostId, DockerEndpoint endpoint, String id);

    /** Creates a network; NAME_TAKEN if one with that name exists. Throws {@code DockerConnectionException}. */
    CreatedResourceDto createNetwork(UUID hostId, DockerEndpoint endpoint, NetworkCreateSpec spec);

    /** Networks a prune removes: user-defined (not bridge, host, none) with no attached container. */
    List<PruneCandidateDto> listPrunable(UUID hostId, DockerEndpoint endpoint);

    /**
     * Connects the container to the network (id or name). Idempotent: already connected is a no-op.
     * Throws {@code DockerConnectionException} with NOT_FOUND for an unknown network or container.
     */
    void connectContainer(UUID hostId, DockerEndpoint endpoint, String networkId, String containerId);

    /** Disconnects the container from the network. Idempotent: not connected is a no-op. NOT_FOUND as above. */
    void disconnectContainer(UUID hostId, DockerEndpoint endpoint, String networkId, String containerId);
}
