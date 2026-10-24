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
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.NetworkDto;
import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;
import dev.bethge.shipir.docker.dto.PruneCandidateDto;
import dev.bethge.shipir.host.dto.HostDeploymentResponse;
import dev.bethge.shipir.host.dto.PrunePreviewResponse;
import dev.bethge.shipir.host.dto.PruneRequest;
import dev.bethge.shipir.host.dto.PruneResponse;
import dev.bethge.shipir.docker.dto.VolumeDto;
import dev.bethge.shipir.compose.execution.RegistryAuthSource;
import dev.bethge.shipir.docker.dto.RegistryLogin;
import dev.bethge.shipir.host.dto.PullImageRequest;
import dev.bethge.shipir.host.dto.PullImageResponse;
import dev.bethge.shipir.permission.CurrentAccess;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import dev.bethge.shipir.streaming.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Read-only resource lists of one environment. Failures become {@link HostException}s with a stable code and
 * never carry connection details.
 */
@Service
public class HostResourcesService {

    private static final Pattern RESOURCE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");

    private static final Logger log = LoggerFactory.getLogger(HostResourcesService.class);

    private static final String PATH_PART = "[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*";
    private static final String DOMAIN = "[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?(?::[0-9]{1,5})?";
    /** {@code [registry[:port]/]path[/path][:tag][@sha256:digest]} - nothing that could be read as a CLI flag or URL. */
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("(?:" + DOMAIN + "/)?" + PATH_PART + "(?:/" + PATH_PART
            + ")*(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?(?:@sha256:[a-f0-9]{64})?");
    private static final java.util.Set<String> DOCKER_HUB_ALIASES =
            java.util.Set.of("docker.io", "index.docker.io", "registry-1.docker.io", "registry.hub.docker.com");

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
    private final ComposeOutputStreamService streams;
    private final RegistryAuthSource registryAuths;

    HostResourcesService(DockerHostRepository hosts, HostEndpointResolver endpoints, ContainerService containers,
                         ImageService images, NetworkService networks, VolumeService volumes,
                         ManagedDeploymentLookup managedDeployments, CurrentAccess access,
                         ComposeOutputStreamService streams, RegistryAuthSource registryAuths) {
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.containers = containers;
        this.images = images;
        this.networks = networks;
        this.volumes = volumes;
        this.managedDeployments = managedDeployments;
        this.access = access;
        this.streams = streams;
        this.registryAuths = registryAuths;
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

    /**
     * Inspect of one container. On a host visible only through deployment grants the container must belong to a
     * visible managed deployment, otherwise 404 RESOURCE_NOT_FOUND. Env values only for ADMIN/OPERATOR (others get keys).
     */
    public ContainerDetailsDto container(UUID hostId, String containerId) {
        requireView(hostId);
        validateResourceId(containerId);
        ContainerDetailsDto details = call(hostId, (id, endpoint) -> containers.inspect(id, endpoint, containerId));
        if (!access.hostFullyVisible(hostId)) {
            UUID managed = details.composeProject() == null ? null
                    : managedDeployments.managedDeploymentIds(hostId).get(details.composeProject());
            if (managed == null || !access.visibleDeploymentIds(hostId).orElseThrow().contains(managed)) {
                throw new HostException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
            }
        }
        boolean mayReadValues = access.subject().map(s -> s.role() != Role.VIEWER).orElse(true);
        return mayReadValues ? details : details.withoutEnvValues();
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

    /** Everything a prune with the given scope would remove now. Host-level OPERATE, like the prune itself. */
    public PrunePreviewResponse prunePreview(UUID hostId, PruneRequest.ImageScope imageScope, boolean includeManagedVolumes) {
        requireOperate(hostId);
        Set<String> managed = managedDeployments.managedDeploymentIds(hostId).keySet();
        return call(hostId, (id, endpoint) -> {
            List<PruneCandidateDto> allVolumes = volumes.listPrunable(id, endpoint);
            List<PruneCandidateDto> keptVolumes = filterVolumes(allVolumes, managed, includeManagedVolumes);
            return new PrunePreviewResponse(
                    PrunePreviewResponse.Category.of(containers.listPrunable(id, endpoint)),
                    PrunePreviewResponse.Category.of(images.listPrunable(id, endpoint, imageScope == PruneRequest.ImageScope.UNUSED)),
                    PrunePreviewResponse.Category.of(keptVolumes),
                    PrunePreviewResponse.Category.of(networks.listPrunable(id, endpoint)),
                    allVolumes.size() - keptVolumes.size());
        });
    }

    /**
     * Removes unused resources in the order containers, volumes, networks, images (candidates are listed per step,
     * so earlier steps free later ones). Nothing is forced: a candidate that became in use or vanished is skipped.
     */
    @Auditable(action = "RESOURCE_PRUNE", targetType = "HOST", targetId = "#hostId",
            details = "{containers: #result.containers.removed, images: #result.images.removed, "
                    + "volumes: #result.volumes.removed, networks: #result.networks.removed, "
                    + "reclaimedBytes: #result.reclaimedBytes, imageScope: #request.images?.name(), "
                    + "includeManagedVolumes: #request.withManagedVolumes()}")
    PruneResponse prune(UUID hostId, PruneRequest request) {
        requireOperate(hostId);
        if (request.nothingSelected()) {
            throw new HostException(HttpStatus.BAD_REQUEST, "NOTHING_SELECTED", "Select at least one resource type");
        }
        Set<String> managed = managedDeployments.managedDeploymentIds(hostId).keySet();
        return call(hostId, (id, endpoint) -> {
            PruneResponse.Result c = PruneResponse.Result.NONE, v = c, n = c, i = c;
            if (request.pruneContainers()) {
                c = removeAll(containers.listPrunable(id, endpoint), cid -> containers.removeContainer(id, endpoint, cid, false));
            }
            if (request.pruneVolumes()) {
                v = removeAll(filterVolumes(volumes.listPrunable(id, endpoint), managed, request.withManagedVolumes()),
                        name -> volumes.removeVolume(id, endpoint, name));
            }
            if (request.pruneNetworks()) {
                n = removeAll(networks.listPrunable(id, endpoint), nid -> networks.removeNetwork(id, endpoint, nid));
            }
            if (request.images() != null) {
                i = removeAll(images.listPrunable(id, endpoint, request.images() == PruneRequest.ImageScope.UNUSED),
                        iid -> images.removeImage(id, endpoint, iid));
            }
            return new PruneResponse(c, i, v, n, c.reclaimedBytes() + i.reclaimedBytes() + v.reclaimedBytes() + n.reclaimedBytes());
        });
    }

    private static List<PruneCandidateDto> filterVolumes(List<PruneCandidateDto> all, Set<String> managedProjects,
                                                         boolean includeManaged) {
        return includeManaged ? all
                : all.stream().filter(v -> v.composeProject() == null || !managedProjects.contains(v.composeProject())).toList();
    }

    /** Removes each candidate; NOT_FOUND / IN_USE / BUILTIN_NETWORK mean it changed meanwhile and count as skipped. */
    private static PruneResponse.Result removeAll(List<PruneCandidateDto> candidates, Consumer<String> remove) {
        int removed = 0;
        int skipped = 0;
        long bytes = 0;
        for (PruneCandidateDto candidate : candidates) {
            try {
                remove.accept(candidate.id());
                removed++;
                bytes += candidate.sizeBytes() == null ? 0 : candidate.sizeBytes();
            } catch (DockerConnectionException e) {
                DockerErrorCode code = e.getCode();
                if (code != DockerErrorCode.NOT_FOUND && code != DockerErrorCode.IN_USE
                        && code != DockerErrorCode.BUILTIN_NETWORK) {
                    throw e;
                }
                skipped++;
            }
        }
        return new PruneResponse.Result(removed, skipped, bytes);
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

    /**
     * Pulls an image on the host. Returns at once with a run id; progress lines are streamed to
     * {@code /topic/deployments/{runId}} (host-level run, visible to users who fully see the host). The stored
     * registry login whose host matches the reference is used for this pull only.
     */
    @Auditable(action = "IMAGE_PULL", targetType = "IMAGE", targetId = "#request.reference()")
    PullImageResponse pullImage(UUID hostId, PullImageRequest request) {
        requireOperate(hostId);
        String reference = request == null || request.reference() == null ? "" : request.reference().trim();
        if (reference.length() > 255 || !IMAGE_REFERENCE.matcher(reference).matches()) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE_REFERENCE", "Invalid image reference");
        }
        DockerEndpoint endpoint = call(hostId, (id, ep) -> ep);
        RegistryLogin login = loginFor(reference);
        String runId = UUID.randomUUID().toString();
        streams.registerRun(runId, hostId, null);
        Consumer<String> sink = streams.start(runId);
        Thread.ofVirtual().name("image-pull-" + runId).start(() -> runPull(hostId, endpoint, reference, login, runId, sink));
        return new PullImageResponse(runId);
    }

    private void runPull(UUID hostId, DockerEndpoint endpoint, String reference, RegistryLogin login, String runId,
                         Consumer<String> sink) {
        RunStatus status = RunStatus.FAILED;
        try {
            sink.accept("Pulling " + reference);
            images.pull(hostId, endpoint, reference, login, sink);
            sink.accept("Pulled " + reference);
            status = RunStatus.DONE;
        } catch (DockerConnectionException e) {
            sink.accept("Pull failed (" + e.getCode() + ")");
        } catch (RuntimeException e) {
            log.warn("Image pull {} failed unexpectedly: {}", runId, e.toString());
            sink.accept("Pull failed (UNKNOWN)");
        } finally {
            streams.complete(runId, status);
        }
    }

    /** The stored login for the registry named by the reference (Docker Hub when it names none), or null. */
    private RegistryLogin loginFor(String reference) {
        String registry = registryOf(reference);
        return registryAuths.auths().stream()
                .filter(a -> registry.equals(canonicalRegistry(a.registryHost())))
                .findFirst()
                .map(a -> new RegistryLogin(a.registryHost(), a.username(), a.secret()))
                .orElse(null);
    }

    private static String registryOf(String reference) {
        int slash = reference.indexOf('/');
        if (slash > 0) {
            String first = reference.substring(0, slash);
            if (first.contains(".") || first.contains(":") || first.equals("localhost")) {
                return canonicalRegistry(first);
            }
        }
        return "docker.io";
    }

    private static String canonicalRegistry(String host) {
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        return DOCKER_HUB_ALIASES.contains(lower) ? "docker.io" : lower;
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
