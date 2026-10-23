package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.NetworkDto;

import java.util.List;
import java.util.UUID;

public interface NetworkService {

    /** All networks of the host with usage derived from the container list. Throws {@code DockerConnectionException}. */
    List<NetworkDto> list(UUID hostId, DockerEndpoint endpoint);
}
