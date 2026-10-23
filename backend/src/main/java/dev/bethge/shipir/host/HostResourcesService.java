package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.NetworkDto;
import dev.bethge.shipir.docker.dto.StackSummaryDto;
import dev.bethge.shipir.host.dto.HostStackResponse;
import dev.bethge.shipir.docker.dto.VolumeDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Read-only resource lists of one environment. Failures become {@link HostException}s with a stable code and
 * never carry connection details.
 */
@Service
public class HostResourcesService {

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final ContainerService containers;
    private final ImageService images;
    private final NetworkService networks;
    private final VolumeService volumes;
    private final ManagedStackLookup managedStacks;

    HostResourcesService(DockerHostRepository hosts, HostEndpointResolver endpoints, ContainerService containers,
                         ImageService images, NetworkService networks, VolumeService volumes,
                         ManagedStackLookup managedStacks) {
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.containers = containers;
        this.images = images;
        this.networks = networks;
        this.volumes = volumes;
        this.managedStacks = managedStacks;
    }

    public List<HostStackResponse> stacks(UUID hostId) {
        List<StackSummaryDto> found = call(hostId, containers::listStacks);
        Map<String, UUID> managed = managedStacks.managedStackIds(hostId);
        return found.stream().map(s -> HostStackResponse.of(s, managed.get(s.name()))).toList();
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

    private <T> List<T> call(UUID hostId, BiFunction<UUID, DockerEndpoint, List<T>> query) {
        DockerHost host = hosts.findById(hostId).orElseThrow(
                () -> new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found"));
        try {
            return query.apply(host.getId(), endpoints.endpointOf(host));
        } catch (DockerConnectionException e) {
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
