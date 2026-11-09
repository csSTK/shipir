package dev.bethge.shipir.docker.client;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.dto.ContainerCreateSpec;
import dev.bethge.shipir.docker.dto.ContainerResourcesSpec;
import dev.bethge.shipir.docker.dto.CreatedResourceDto;
import dev.bethge.shipir.docker.dto.RecreateLeftoverAction;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;

import java.util.List;
import java.util.UUID;

public interface ContainerService {

    /** All containers of the host including stopped ones. Throws {@code DockerConnectionException}. */
    List<ContainerDto> listContainers(UUID hostId, DockerEndpoint endpoint);

    /**
     * Inspect of one container (env with values) without the image's own env: {@code imageEnv} is empty and the image
     * is not inspected. For callers that only need container fields. Throws {@code DockerConnectionException},
     * NOT_FOUND if unknown.
     */
    ContainerDetailsDto inspect(UUID hostId, DockerEndpoint endpoint, String id);

    /** Like {@link #inspect} plus {@code imageEnv} (one extra image inspect); for the details endpoint only. */
    ContainerDetailsDto inspectWithImageEnv(UUID hostId, DockerEndpoint endpoint, String id);

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

    /**
     * Replaces the container {@code id} by a new one built from {@code spec}, keeping its name, labels and every
     * host setting the spec does not model (resource limits, devices, ...), plus hostname, tty/stdin and, if the image
     * is unchanged, user, working dir, entrypoint and healthcheck. StopSignal and StopTimeout are not carried over
     * (docker-java's ContainerConfig has no getters for them). Order: create the new container under a
     * temporary name, stop and rename the old one, rename and start the new one (only if the old one was running),
     * remove the old one (anonymous volumes are kept). On any failure the new container is removed and the old one is
     * renamed back and restarted. {@code spec.name()} and {@code spec.start()} are ignored. Throws
     * {@code DockerConnectionException}: NOT_FOUND (container or image missing), RECREATE_FAILED (rolled back).
     */
    CreatedResourceDto recreateContainer(UUID hostId, DockerEndpoint endpoint, String id, ContainerCreateSpec spec);

    /**
     * Cleans up after a recreate that was cut short (Shipir died between renaming the old container to
     * {@code <name>-shipir-old-<8 hex>} and finishing). The recreate appends {@code -r} to that name when the old
     * container was running, which is how the prior state survives a crash (leftovers without it, e.g. from before this
     * convention, count as stopped). For each leftover: if a container with the
     * original name exists, a running-before leftover first gets that container started when it is not running (crash
     * after the new one took the name but before it started), then the leftover is removed (anonymous volumes are
     * kept); otherwise the leftover is renamed back and started if it was running. A name that is taken while we act
     * is SKIPPED; a failing action is FAILED and does not stop the others. Leftovers of a recreate running right now in
     * this process are ignored. Assumes a single Shipir instance per Docker host. Throws
     * {@code DockerConnectionException} if the host is unreachable.
     */
    List<RecreateLeftoverAction> reconcileRecreateLeftovers(UUID hostId, DockerEndpoint endpoint);

    /**
     * Applies resource limits to an existing container without restarting it ({@code docker update}). Throws
     * {@code DockerConnectionException}: NOT_FOUND, UPDATE_REJECTED (the daemon refused the values).
     */
    void updateResources(UUID hostId, DockerEndpoint endpoint, String id, ContainerResourcesSpec spec);

    /** Containers a prune removes: every container that is not running, paused or restarting (size = writable layer). */
    List<PruneCandidateDto> listPrunable(UUID hostId, DockerEndpoint endpoint);
}
