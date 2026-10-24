package dev.bethge.shipir.host;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.NetworkDto;
import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;
import dev.bethge.shipir.host.dto.HostDeploymentResponse;
import dev.bethge.shipir.docker.dto.VolumeDto;
import dev.bethge.shipir.permission.CurrentAccess;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Read-only resource lists of one environment. Failures become {@link HostException}s with a stable code and
 * never carry connection details.
 */
@Service
public class HostResourcesService {

    private static final Pattern RESOURCE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");

    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int MAX_TIMEOUT_SECONDS = 300;

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final ContainerService containers;
    private final ImageService images;
    private final NetworkService networks;
    private final VolumeService volumes;
    private final ManagedDeploymentLookup managedDeployments;
    private final CurrentAccess access;

    HostResourcesService(DockerHostRepository hosts, HostEndpointResolver endpoints, ContainerService containers,
                         ImageService images, NetworkService networks, VolumeService volumes,
                         ManagedDeploymentLookup managedDeployments, CurrentAccess access) {
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.containers = containers;
        this.images = images;
        this.networks = networks;
        this.volumes = volumes;
        this.managedDeployments = managedDeployments;
        this.access = access;
    }

    /**
     * Fully visible host: all projects. Host visible only through deployment grants: just the managed deployments the
     * user may see (unmanaged projects are hidden). Not visible: 404 HOST_NOT_FOUND.
     */
    public List<HostDeploymentResponse> deployments(UUID hostId) {
        requireView(hostId);
        List<DeploymentSummaryDto> found = call(hostId, containers::listDeployments);
        Map<String, UUID> managed = managedDeployments.managedDeploymentIds(hostId);
        var visible = access.visibleDeploymentIds(hostId);
        return found.stream()
                .map(s -> HostDeploymentResponse.of(s, managed.get(s.name())))
                .filter(r -> access.hostFullyVisible(hostId)
                        || (r.managedDeploymentId() != null && visible.orElseThrow().contains(r.managedDeploymentId())))
                .toList();
    }

    /** Containers of a fully visible host; with deployment grants only: those of the visible managed deployments. */
    public List<ContainerDto> containers(UUID hostId) {
        requireView(hostId);
        List<ContainerDto> all = call(hostId, containers::listContainers);
        if (access.hostFullyVisible(hostId)) {
            return all;
        }
        Map<String, UUID> managed = managedDeployments.managedDeploymentIds(hostId);
        var visible = access.visibleDeploymentIds(hostId).orElseThrow();
        return all.stream()
                .filter(c -> c.composeProject() != null && managed.get(c.composeProject()) != null
                        && visible.contains(managed.get(c.composeProject())))
                .toList();
    }

    /** Images, networks and volumes belong to the whole host: empty unless the host is fully visible. */
    public List<ImageDto> images(UUID hostId) {
        requireView(hostId);
        return access.hostFullyVisible(hostId) ? call(hostId, images::list) : List.of();
    }

    public List<NetworkDto> networks(UUID hostId) {
        requireView(hostId);
        return access.hostFullyVisible(hostId) ? call(hostId, networks::list) : List.of();
    }

    public List<VolumeDto> volumes(UUID hostId) {
        requireView(hostId);
        return access.hostFullyVisible(hostId) ? call(hostId, volumes::list) : List.of();
    }

    /** 404 when the host is not visible to the signed-in user (existence is not revealed). */
    private void requireView(UUID hostId) {
        if (!access.canView(hostId)) {
            throw new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found");
        }
    }

    /** Container actions and deletes act on the whole host: 404 if invisible, 403 without a host-level OPERATE grant. */
    private void requireOperate(UUID hostId) {
        requireView(hostId);
        if (!access.canOperate(hostId)) {
            throw new HostException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
        }
    }

    @Auditable(action = "CONTAINER_DELETE", targetType = "CONTAINER", targetId = "#containerId")
    void removeContainer(UUID hostId, String containerId, boolean force) {
        requireOperate(hostId);
        validateResourceId(containerId);
        call(hostId, (id, endpoint) -> {
            containers.removeContainer(id, endpoint, containerId, force);
            return null;
        });
    }

    @Auditable(action = "IMAGE_DELETE", targetType = "IMAGE", targetId = "#imageId")
    void removeImage(UUID hostId, String imageId) {
        requireOperate(hostId);
        validateResourceId(imageId);
        call(hostId, (id, endpoint) -> {
            images.removeImage(id, endpoint, imageId);
            return null;
        });
    }

    @Auditable(action = "VOLUME_DELETE", targetType = "VOLUME", targetId = "#name")
    void removeVolume(UUID hostId, String name) {
        requireOperate(hostId);
        validateResourceId(name);
        call(hostId, (id, endpoint) -> {
            volumes.removeVolume(id, endpoint, name);
            return null;
        });
    }

    @Auditable(action = "NETWORK_DELETE", targetType = "NETWORK", targetId = "#networkId")
    void removeNetwork(UUID hostId, String networkId) {
        requireOperate(hostId);
        validateResourceId(networkId);
        call(hostId, (id, endpoint) -> {
            networks.removeNetwork(id, endpoint, networkId);
            return null;
        });
    }

    @Auditable(action = "CONTAINER_START", targetType = "CONTAINER", targetId = "#containerId")
    void startContainer(UUID hostId, String containerId) {
        requireOperate(hostId);
        validateResourceId(containerId);
        call(hostId, (id, endpoint) -> {
            containers.startContainer(id, endpoint, containerId);
            return null;
        });
    }

    @Auditable(action = "CONTAINER_STOP", targetType = "CONTAINER", targetId = "#containerId")
    void stopContainer(UUID hostId, String containerId, String timeout) {
        requireOperate(hostId);
        validateResourceId(containerId);
        int seconds = parseTimeout(timeout);
        call(hostId, (id, endpoint) -> {
            containers.stopContainer(id, endpoint, containerId, seconds);
            return null;
        });
    }

    @Auditable(action = "CONTAINER_RESTART", targetType = "CONTAINER", targetId = "#containerId")
    void restartContainer(UUID hostId, String containerId, String timeout) {
        requireOperate(hostId);
        validateResourceId(containerId);
        int seconds = parseTimeout(timeout);
        call(hostId, (id, endpoint) -> {
            containers.restartContainer(id, endpoint, containerId, seconds);
            return null;
        });
    }

    @Auditable(action = "CONTAINER_PAUSE", targetType = "CONTAINER", targetId = "#containerId")
    void pauseContainer(UUID hostId, String containerId) {
        requireOperate(hostId);
        validateResourceId(containerId);
        call(hostId, (id, endpoint) -> {
            containers.pauseContainer(id, endpoint, containerId);
            return null;
        });
    }

    @Auditable(action = "CONTAINER_UNPAUSE", targetType = "CONTAINER", targetId = "#containerId")
    void unpauseContainer(UUID hostId, String containerId) {
        requireOperate(hostId);
        validateResourceId(containerId);
        call(hostId, (id, endpoint) -> {
            containers.unpauseContainer(id, endpoint, containerId);
            return null;
        });
    }

    @Auditable(action = "CONTAINER_KILL", targetType = "CONTAINER", targetId = "#containerId")
    void killContainer(UUID hostId, String containerId) {
        requireOperate(hostId);
        validateResourceId(containerId);
        call(hostId, (id, endpoint) -> {
            containers.killContainer(id, endpoint, containerId);
            return null;
        });
    }

    /** Grace period in seconds: absent = 10, otherwise an integer in 0..300. */
    private static int parseTimeout(String raw) {
        if (raw == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value >= 0 && value <= MAX_TIMEOUT_SECONDS) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the 400 below
        }
        throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "timeout must be a whole number of seconds between 0 and " + MAX_TIMEOUT_SECONDS);
    }

    /** Ids and names are a single path segment of plain characters; nothing else reaches the daemon. */
    private static void validateResourceId(String value) {
        if (value == null || !RESOURCE_ID.matcher(value).matches()) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_RESOURCE_ID", "Invalid resource id");
        }
    }

    private <T> T call(UUID hostId, BiFunction<UUID, DockerEndpoint, T> query) {
        DockerHost host = hosts.findById(hostId).orElseThrow(
                () -> new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found"));
        try {
            return query.apply(host.getId(), endpoints.endpointOf(host));
        } catch (DockerConnectionException e) {
            if (e.getCode() == DockerErrorCode.NOT_FOUND) {
                throw new HostException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
            }
            if (e.getCode() == DockerErrorCode.IN_USE) {
                throw new HostException(HttpStatus.CONFLICT, "RESOURCE_IN_USE", "Resource is in use");
            }
            if (e.getCode() == DockerErrorCode.INVALID_STATE) {
                throw new HostException(HttpStatus.CONFLICT, "INVALID_CONTAINER_STATE",
                        "The container is not in a state that allows this action");
            }
            if (e.getCode() == DockerErrorCode.BUILTIN_NETWORK) {
                throw new HostException(HttpStatus.CONFLICT, "RESOURCE_IN_USE",
                        "Built-in networks cannot be removed");
            }
            throw badGateway(e.getCode().name(), "Environment could not be reached");
        } catch (EncryptionKeyMissingException e) {
            throw badGateway("ENCRYPTION_KEY_MISSING", "No encryption key is configured");
        } catch (SecretDecryptionException e) {
            throw badGateway("DECRYPTION_FAILED", "Stored connection secrets could not be decrypted");
        }
    }

    private static HostException badGateway(String code, String message) {
        return new HostException(HttpStatus.BAD_GATEWAY, code, message);
    }
}
