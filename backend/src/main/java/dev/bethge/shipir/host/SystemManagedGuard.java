package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.ViewerErrorCodes;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.VolumeService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.NetworkDto;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import dev.bethge.shipir.docker.dto.VolumeDto;
import dev.bethge.shipir.permission.CurrentAccess;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
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

    /** External network the managed proxy template attaches to ({@code managed-*.yaml}); routed containers join it too. */
    private static final String PROXY_NETWORK = "shipir-proxy";

    private final ManagedDeploymentLookup managed;
    private final CurrentAccess access;
    private final ContainerService containers;
    private final VolumeService volumes;
    private final NetworkService networks;
    private final ImageService images;
    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;

    SystemManagedGuard(ManagedDeploymentLookup managed, CurrentAccess access, ContainerService containers,
                       VolumeService volumes, NetworkService networks, ImageService images,
                       DockerHostRepository hosts, HostEndpointResolver endpoints) {
        this.networks = networks;
        this.images = images;
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

    /**
     * 403 when the network (id, id prefix or name) belongs to a system-managed project: the external proxy network,
     * a network a container of the project is attached to, or a Compose default network of the project. Anything
     * Docker does not know is left to the action itself to report (404).
     */
    void requireNotSystemManagedNetwork(UUID hostId, String network) {
        Set<String> restricted = restrictedNetworkNames(hostId);
        if (restricted.isEmpty()) {
            return;
        }
        for (NetworkDto n : call(hostId, networks::list)) {
            if (matchesNetwork(n, network) && restricted.contains(n.name())) {
                throw forbidden();
            }
        }
    }

    /** 403 when a network of that name would be created that the system-managed project uses (name squatting). */
    void requireNotSystemManagedNetworkName(UUID hostId, String name) {
        if (restrictedNetworkNames(hostId).contains(name)) {
            throw forbidden();
        }
    }

    /** Prune candidates without the networks of system-managed projects (unchanged for ADMIN). */
    List<PruneCandidateDto> withoutRestrictedNetworks(UUID hostId, List<PruneCandidateDto> candidates) {
        Set<String> restricted = restrictedNetworkNames(hostId);
        return restricted.isEmpty() ? candidates : candidates.stream().filter(c -> !restricted.contains(c.name())).toList();
    }

    /** 403 when the image (id, id prefix or tag) is the image of a container of a system-managed project. */
    void requireNotSystemManagedImage(UUID hostId, String image) {
        Set<String> used = restrictedImageRefs(hostId);
        if (used.isEmpty()) {
            return;
        }
        for (ImageDto i : call(hostId, images::list)) {
            if (matchesImage(i, image) && isRestrictedImage(i, used)) {
                throw forbidden();
            }
        }
    }

    /** Prune candidates without the images of system-managed projects (unchanged for ADMIN). */
    List<PruneCandidateDto> withoutRestrictedImages(UUID hostId, List<PruneCandidateDto> candidates) {
        Set<String> used = restrictedImageRefs(hostId);
        if (used.isEmpty()) {
            return candidates;
        }
        List<ImageDto> all = call(hostId, images::list);
        return candidates.stream().filter(c -> all.stream().noneMatch(i -> i.id().equals(c.id())
                && isRestrictedImage(i, used))).toList();
    }

    /** Networks the caller must not touch (empty for ADMIN or without a system-managed project). */
    private Set<String> restrictedNetworkNames(UUID hostId) {
        Set<String> projects = restrictedProjects(hostId);
        if (projects.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        names.add(PROXY_NETWORK);
        names.addAll(ManagedProxyTemplateResources.networks());
        names.addAll(storedProxyResources(hostId).networks());
        try {
            for (ContainerDto c : call(hostId, containers::listContainers)) {
                if (c.composeProject() == null || !projects.contains(c.composeProject())) {
                    continue;
                }
                ContainerDetailsDto details = call(hostId, (id, endpoint) -> containers.inspect(id, endpoint, c.id()));
                if (details != null && details.networks() != null) {
                    details.networks().stream().map(ContainerDetailsDto.Network::name).filter(n -> n != null).forEach(names::add);
                }
            }
            for (NetworkDto n : call(hostId, networks::list)) {
                for (String project : projects) {
                    if (n.name() != null && n.name().startsWith(project + "_")) {
                        names.add(n.name());
                    }
                }
            }
        } catch (NotFound e) {
            // a container that vanished meanwhile has no networks left to protect
        }
        return names;
    }

    /** References as given used by containers of system-managed projects, plus the images of the bundled proxy templates. */
    private Set<String> restrictedImageRefs(UUID hostId) {
        Set<String> projects = restrictedProjects(hostId);
        if (projects.isEmpty()) {
            return Set.of();
        }
        // the bundled proxy templates' images count too: after compose down no container names them
        Set<String> refs = new HashSet<>(ManagedProxyTemplateResources.images());
        // and so do the images of the proxy's stored compose file (ADMIN-customised, or before a template bump)
        refs.addAll(storedProxyResources(hostId).images());
        for (ContainerDto c : call(hostId, containers::listContainers)) {
            if (c.composeProject() != null && projects.contains(c.composeProject()) && c.image() != null) {
                refs.add(c.image());
            }
        }
        return refs;
    }

    /** Images and networks the system-managed deployments' stored compose files declare. */
    private ManagedProxyTemplateResources.Resources storedProxyResources(UUID hostId) {
        Set<String> imageRefs = new HashSet<>();
        Set<String> networkNames = new HashSet<>();
        for (String compose : managed.systemManagedComposeFiles(hostId)) {
            ManagedProxyTemplateResources.Resources r = ManagedProxyTemplateResources.parse(compose);
            imageRefs.addAll(r.images());
            networkNames.addAll(r.networks());
        }
        return new ManagedProxyTemplateResources.Resources(imageRefs, networkNames);
    }

    private static boolean matchesNetwork(NetworkDto n, String ref) {
        return ref != null && (ref.equals(n.name()) || (n.id() != null && !ref.isEmpty() && n.id().startsWith(ref)));
    }

    /**
     * True when {@code ref} (id, id prefix with or without {@code sha256:}, tag with or without {@code :latest}, or a
     * digest reference) names this image. Digest-pulled images have no tags, so repoDigests are compared too.
     */
    private static boolean matchesImage(ImageDto i, String ref) {
        if (ref == null || ref.isEmpty()) {
            return false;
        }
        String id = i.id() == null ? "" : stripSha(i.id());
        if (ref.matches("(sha256:)?[0-9a-f]+") && id.startsWith(stripSha(ref))) {
            return true;
        }
        if (i.repoTags() != null && i.repoTags().stream().anyMatch(t -> withLatest(t).equals(withLatest(ref)))) {
            return true;
        }
        int at = ref.indexOf("@sha256:");
        if (at >= 0 && i.repoDigests() != null) {
            String digest = ref.substring(at);
            return i.repoDigests().stream().anyMatch(d -> d.endsWith(digest));
        }
        return false;
    }

    private static boolean isRestrictedImage(ImageDto image, Set<String> used) {
        return used.stream().anyMatch(u -> matchesImage(image, u));
    }

    private static String stripSha(String s) {
        return s.startsWith("sha256:") ? s.substring("sha256:".length()) : s;
    }

    /** A tag reference without a tag part means {@code :latest}; ids and digest references stay as they are. */
    private static String withLatest(String ref) {
        if (ref.contains("@")) {
            return ref;
        }
        int slash = ref.lastIndexOf('/');
        return ref.indexOf(':', slash + 1) < 0 ? ref + ":latest" : ref;
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
