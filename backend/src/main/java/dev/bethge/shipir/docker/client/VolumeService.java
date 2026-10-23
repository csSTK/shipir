package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.VolumeDto;

import java.util.List;
import java.util.UUID;

public interface VolumeService {

    /** All volumes of the host with usage derived from the container list. Throws {@code DockerConnectionException}. */
    List<VolumeDto> list(UUID hostId, DockerEndpoint endpoint);
}
