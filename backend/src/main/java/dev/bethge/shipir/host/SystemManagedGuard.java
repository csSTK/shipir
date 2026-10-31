package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.ViewerErrorCodes;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.VolumeDto;
import dev.bethge.shipir.permission.CurrentAccess;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The managed proxy deployment is system-managed: its containers and volumes (certificate keys, DNS config) are
 * ADMIN only, whatever host-level or deployment grants a caller has. This is the one place that decides which
 * containers and volumes of a host belong to a system-managed project; services that act on a container or volume by id
 * ask it. ADMIN callers are never looked up. Fails closed: if the owner cannot be determined (Docker unreachable) the
 * caller gets a 502, not access.
 */
@Component
class SystemManagedGuard {

    private final ManagedDeploymentLookup managed;
    private final CurrentAccess access;
    private final ContainerService containers;
    private final VolumeService volumes;
    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;

    SystemManagedGuard(ManagedDeploymentLookup managed, CurrentAccess access, ContainerService containers,
                       VolumeService volumes, DockerHostRepository hosts, HostEndpointResolver endpoints) {
        this.managed = managed;
        this.access = access;
        this.containers = containers;
        this.volumes = volumes;
        this.hosts = hosts;
        this.endpoints = endpoints;
    }

    /** Names of the system-managed Compose projects of the host that this caller must not touch (empty for ADMIN). */
    Set<String> restrictedProjects(UUID hostId) {
        return access.isAdmin() ? Set.of() : managed.systemManagedNames(hostId);
    }

    /** 403 when the container belongs to a system-managed project and the caller is not ADMIN. */
    void requireNotSystemManaged(UUID hostId, ContainerDetailsDto details) {
        if (details != null && details.composeProject() != null
                && restrictedProjects(hostId).contains(details.composeProject())) {
            throw forbidden();
        }
    }

    /**
     * Looks the container up and applies {@link #requireNotSystemManaged}. A container Docker does not know is left
     * to the action itself to report (404).
     */
    void requireNotSystemManagedContainer(UUID hostId, String containerId) {
        if (restrictedProjects(hostId).isEmpty()) {
            return;
        }
        ContainerDetailsDto details;
        try {
            details = call(hostId, (id, endpoint) -> containers.inspect(id, endpoint, containerId));
        } catch (NotFound e) {
            return;
        }
        requireNotSystemManaged(hostId, details);
    }

    /** 403 when the volume belongs to a system-managed project (label) or is mounted by one of its containers. */
    void requireNotSystemManagedVolume(UUID hostId, String volume) {
        if (restrictedVolumeNames(hostId).contains(volume)) {
            throw forbidden();
        }
    }

    /** Volumes of the host the caller must not touch: labelled with, or mounted by a container of, a system-managed project. */
    Set<String> restrictedVolumeNames(UUID hostId) {
        Set<String> projects = restrictedProjects(hostId);
        if (projects.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        try {
            for (VolumeDto v : call(hostId, volumes::list)) {
                if (v.composeProject() != null && projects.contains(v.composeProject())) {
                    names.add(v.name());
                }
            }
            for (ContainerDto c : call(hostId, containers::listContainers)) {
                if (c.composeProject() == null || !projects.contains(c.composeProject())) {
                    continue;
                }
                ContainerDetailsDto details = call(hostId, (id, endpoint) -> containers.inspect(id, endpoint, c.id()));
                if (details != null && details.mounts() != null) {
                    details.mounts().stream().map(ContainerDetailsDto.Mount::name).filter(n -> n != null).forEach(names::add);
                }
            }
        } catch (NotFound e) {
            // a container that vanished meanwhile has no mounts left to protect
        }
        return names;
    }

    private HostException forbidden() {
        return new HostException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    /** Internal marker: Docker reported the looked-up resource as missing. */
    private static final class NotFound extends RuntimeException {
        NotFound() {
            super(null, null, false, false);
        }
    }

    private <T> T call(UUID hostId, BiFunction<UUID, DockerEndpoint, T> query) {
        DockerHost host = hosts.findById(hostId).orElseThrow(
                () -> new HostException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found"));
        try {
            return query.apply(host.getId(), endpoints.endpointOf(host));
        } catch (DockerConnectionException e) {
            if (e.getCode() == DockerErrorCode.NOT_FOUND) {
                throw new NotFound();
            }
            throw new HostException(HttpStatus.BAD_GATEWAY, ViewerErrorCodes.forViewer(e.getCode(), false),
                    "Environment could not be reached");
        } catch (EncryptionKeyMissingException e) {
            throw new HostException(HttpStatus.BAD_GATEWAY, "ENCRYPTION_KEY_MISSING", "No encryption key is configured");
        } catch (SecretDecryptionException e) {
            throw new HostException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED",
                    "Stored connection secrets could not be decrypted");
        }
    }
}
