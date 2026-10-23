package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;

import java.util.List;
import java.util.UUID;

public interface ContainerService {

    /** All containers of the host including stopped ones. Throws {@code DockerConnectionException}. */
    List<ContainerDto> listContainers(UUID hostId, DockerEndpoint endpoint);

    /** Compose projects of the host, grouped by the {@code com.docker.compose.project} label, sorted by name. */
    List<DeploymentSummaryDto> listDeployments(UUID hostId, DockerEndpoint endpoint);

    /**
     * Removes a container; a running one only with {@code force}. Anonymous volumes are kept.
     * Throws {@code DockerConnectionException} with NOT_FOUND or IN_USE (running, no force).
     */
    void removeContainer(UUID hostId, DockerEndpoint endpoint, String id, boolean force);
}
