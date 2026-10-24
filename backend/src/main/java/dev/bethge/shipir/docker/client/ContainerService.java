package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.ContainerCreateSpec;
import dev.bethge.shipir.docker.dto.CreatedResourceDto;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;

import java.util.List;
import java.util.UUID;

public interface ContainerService {

    /** All containers of the host including stopped ones. Throws {@code DockerConnectionException}. */
    List<ContainerDto> listContainers(UUID hostId, DockerEndpoint endpoint);

    /** Inspect of one container (env with values). Throws {@code DockerConnectionException}, NOT_FOUND if unknown. */
    ContainerDetailsDto inspect(UUID hostId, DockerEndpoint endpoint, String id);

    /** Compose projects of the host, grouped by the {@code com.docker.compose.project} label, sorted by name. */
    List<DeploymentSummaryDto> listDeployments(UUID hostId, DockerEndpoint endpoint);

    /**
     * Removes a container; a running one only with {@code force}. Anonymous volumes are kept.
     * Throws {@code DockerConnectionException} with NOT_FOUND or IN_USE (running, no force).
     */
    void removeContainer(UUID hostId, DockerEndpoint endpoint, String id, boolean force);

    /**
     * Lifecycle actions. All throw {@code DockerConnectionException}: NOT_FOUND for an unknown container,
     * INVALID_STATE when the state does not fit (start on running, stop on stopped, pause on stopped/paused,
     * unpause on not paused, kill on not running).
     */
    void startContainer(UUID hostId, DockerEndpoint endpoint, String id);

    /** Stops with SIGTERM, SIGKILL after {@code timeoutSeconds}; the call may take that long. */
    void stopContainer(UUID hostId, DockerEndpoint endpoint, String id, int timeoutSeconds);

    /** Restarts; like stop it may take up to {@code timeoutSeconds} extra. */
    void restartContainer(UUID hostId, DockerEndpoint endpoint, String id, int timeoutSeconds);

    void pauseContainer(UUID hostId, DockerEndpoint endpoint, String id);

    void unpauseContainer(UUID hostId, DockerEndpoint endpoint, String id);

    /** Sends SIGKILL to a running container. */
    void killContainer(UUID hostId, DockerEndpoint endpoint, String id);

    /**
     * Creates a container from an already validated spec and starts it when {@code spec.start()}. Throws
     * {@code DockerConnectionException}: NAME_TAKEN, NOT_FOUND (image or network missing), START_FAILED (created, did
     * not start; the container is removed again).
     */
    CreatedResourceDto createContainer(UUID hostId, DockerEndpoint endpoint, ContainerCreateSpec spec);

    /** Containers a prune removes: every container that is not running, paused or restarting (size = writable layer). */
    List<PruneCandidateDto> listPrunable(UUID hostId, DockerEndpoint endpoint);
}
