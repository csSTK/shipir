package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import dev.bethge.shipir.docker.dto.VolumeDto;

import java.util.List;
import java.util.UUID;

public interface VolumeService {

    /** All volumes of the host with usage derived from the container list. Throws {@code DockerConnectionException}. */
    List<VolumeDto> list(UUID hostId, DockerEndpoint endpoint);

    /** Removes a volume, never forced. Throws {@code DockerConnectionException} with NOT_FOUND or IN_USE. */
    void removeVolume(UUID hostId, DockerEndpoint endpoint, String name);

    /** Volumes a prune removes: not mounted by any container (any state). {@code composeProject} is the Compose label. */
    List<PruneCandidateDto> listPrunable(UUID hostId, DockerEndpoint endpoint);
}
