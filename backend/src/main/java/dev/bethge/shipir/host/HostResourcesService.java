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

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final ContainerService containers;
    private final ImageService images;
    private final NetworkService networks;
    private final VolumeService volumes;
    private final ManagedDeploymentLookup managedDeployments;

    HostResourcesService(DockerHostRepository hosts, HostEndpointResolver endpoints, ContainerService containers,
                         ImageService images, NetworkService networks, VolumeService volumes,
                         ManagedDeploymentLookup managedDeployments) {
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.containers = containers;
        this.images = images;
        this.networks = networks;
        this.volumes = volumes;
        this.managedDeployments = managedDeployments;
    }

    public List<HostDeploymentResponse> deployments(UUID hostId) {
        List<DeploymentSummaryDto> found = call(hostId, containers::listDeployments);
        Map<String, UUID> managed = managedDeployments.managedDeploymentIds(hostId);
        return found.stream().map(s -> HostDeploymentResponse.of(s, managed.get(s.name()))).toList();
    }

    public List<ContainerDto> containers(UUID hostId) {
        return call(hostId, containers::listContainers);
    }

    public List<ImageDto> images(UUID hostId) {
        return call(hostId, images::list);
    }

    public List<NetworkDto> networks(UUID hostId) {
        return call(hostId, networks::list);
    }

    public List<VolumeDto> volumes(UUID hostId) {
        return call(hostId, volumes::list);
    }

    @Auditable(action = "CONTAINER_DELETE", targetType = "CONTAINER", targetId = "#containerId")
    void removeContainer(UUID hostId, String containerId, boolean force) {
        validateResourceId(containerId);
        call(hostId, (id, endpoint) -> {
            containers.removeContainer(id, endpoint, containerId, force);
            return null;
        });
    }

    @Auditable(action = "IMAGE_DELETE", targetType = "IMAGE", targetId = "#imageId")
    void removeImage(UUID hostId, String imageId) {
        validateResourceId(imageId);
        call(hostId, (id, endpoint) -> {
            images.removeImage(id, endpoint, imageId);
            return null;
        });
    }

    @Auditable(action = "VOLUME_DELETE", targetType = "VOLUME", targetId = "#name")
    void removeVolume(UUID hostId, String name) {
        validateResourceId(name);
        call(hostId, (id, endpoint) -> {
            volumes.removeVolume(id, endpoint, name);
            return null;
        });
    }

    @Auditable(action = "NETWORK_DELETE", targetType = "NETWORK", targetId = "#networkId")
    void removeNetwork(UUID hostId, String networkId) {
        validateResourceId(networkId);
        call(hostId, (id, endpoint) -> {
            networks.removeNetwork(id, endpoint, networkId);
            return null;
        });
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
