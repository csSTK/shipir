package dev.bethge.shipir.host;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.DockerSystemService;
import dev.bethge.shipir.docker.dto.DiskUsageDto;
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
import dev.bethge.shipir.docker.dto.ContainerCreateSpec;
import dev.bethge.shipir.docker.dto.ContainerResourcesSpec;
import dev.bethge.shipir.host.dto.UpdateContainerResourcesRequest;
import dev.bethge.shipir.docker.dto.CreatedResourceDto;
import dev.bethge.shipir.docker.dto.NetworkCreateSpec;
import dev.bethge.shipir.docker.dto.VolumeCreateSpec;
import dev.bethge.shipir.host.dto.CreateContainerRequest;
import dev.bethge.shipir.host.dto.CreateNetworkRequest;
import dev.bethge.shipir.host.dto.CreateVolumeRequest;
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
    private final ImageUpdateStore imageUpdates;
    private final DockerSystemService system;

    HostResourcesService(DockerHostRepository hosts, HostEndpointResolver endpoints, ContainerService containers,
                         ImageService images, NetworkService networks, VolumeService volumes,
                         ManagedDeploymentLookup managedDeployments, CurrentAccess access,
                         ComposeOutputStreamService streams, RegistryAuthSource registryAuths,
                         ImageUpdateStore imageUpdates, DockerSystemService system) {
        this.system = system;
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
        this.imageUpdates = imageUpdates;
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
        Set<String> outdatedProjects = projectsWithUpdates(hostId);
        return found.stream()
                .map(s -> HostDeploymentResponse.of(s, managed.get(s.name()), outdatedProjects.contains(s.name())))
                .filter(r -> access.hostFullyVisible(hostId)
                        || (r.managedDeploymentId() != null && visible.orElseThrow().contains(r.managedDeploymentId())))
                .toList();
    }

    /** Compose projects with a container whose image has a newer remote digest (no extra Docker call without any). */
    private Set<String> projectsWithUpdates(UUID hostId) {
        if (imageUpdates.isEmpty(hostId)) {
            return Set.of();
        }
        try {
            return call(hostId, containers::listContainers).stream()
                    .filter(c -> c.composeProject() != null && hasUpdate(hostId, c.image()))
                    .map(ContainerDto::composeProject)
                    .collect(java.util.stream.Collectors.toSet());
        } catch (HostException e) {
            return Set.of();
        }
    }

    private boolean hasUpdate(UUID hostId, String image) {
        ImageReference ref = ImageReference.parse(image);
        return ref != null && imageUpdates.hasUpdate(hostId, ref.key());
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
        if (!access.hostFullyVisible(hostId)) {
            return List.of();
        }
        return call(hostId, images::list).stream()
                .map(i -> i.withUpdateAvailable(i.repoTags().stream().anyMatch(t -> hasUpdate(hostId, t))))
                .toList();
    }

    /**
     * Disk usage of the whole host (all images, containers, volumes and build cache). The numbers are host-wide, so a
     * host visible only through deployment grants gets 403 rather than a partial total; invisible hosts 404.
     */
    public DiskUsageDto diskUsage(UUID hostId) {
        requireView(hostId);
        if (!access.hostFullyVisible(hostId)) {
            throw new HostException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
        }
        return call(hostId, system::diskUsage);
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

    // ---- create -------------------------------------------------------------------------------------------

    private static final Pattern RESOURCE_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern IPV4_CIDR = Pattern.compile(
            "((?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})/(\\d{1,2})");
    private static final Pattern IPV6_CIDR = Pattern.compile("[0-9a-fA-F:]{2,39}/(\\d{1,3})");
    private static final Set<String> RESTART_POLICIES = Set.of("no", "always", "unless-stopped", "on-failure");
    private static final Set<String> NETWORK_DRIVERS = Set.of("bridge", "overlay", "macvlan", "ipvlan");
    private static final int MAX_LIST = 100;

    /**
     * Creates (and optionally starts) a container. Host-level OPERATE; privileged mode, host networking and bind mounts
     * additionally need ADMIN (403 ADMIN_REQUIRED). 400 INVALID_CONTAINER_SPEC lists every violation, 409 NAME_TAKEN,
     * 404 RESOURCE_NOT_FOUND (image/network missing), 409 CONTAINER_START_FAILED (created, not started: removed again).
     */
    @Auditable(action = "CONTAINER_CREATE", targetType = "CONTAINER",
            targetId = "#request?.name() != null ? #request.name() : #request?.image()")
    CreatedResourceDto createContainer(UUID hostId, CreateContainerRequest request) {
        requireOperate(hostId);
        ContainerCreateSpec spec = authorizedSpec(request);
        return call(hostId, (id, endpoint) -> containers.createContainer(id, endpoint, spec));
    }

    /** The create validation and the ADMIN gate, shared by create, duplicate and recreate so none can be weaker. */
    private ContainerCreateSpec authorizedSpec(CreateContainerRequest request) {
        ContainerCreateSpec spec = validateContainer(request);
        boolean bind = spec.mounts().stream().anyMatch(m -> "BIND".equals(m.type()));
        if ((spec.privileged() || spec.hostNetwork() || bind) && !access.isAdmin()) {
            throw new HostException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED",
                    "Privileged mode, host networking and bind mounts need the ADMIN role");
        }
        return spec;
    }

    /** Compose owns its containers: changing one behind its back would be undone by the next {@code up}. */
    private ContainerDetailsDto requireNotComposeManaged(UUID hostId, String containerId) {
        ContainerDetailsDto existing = call(hostId, (id, endpoint) -> containers.inspect(id, endpoint, containerId));
        if (existing.composeProject() != null) {
            throw new HostException(HttpStatus.CONFLICT, "MANAGED_BY_COMPOSE",
                    "This container belongs to a Compose project; change it through its deployment");
        }
        return existing;
    }

    /**
     * Replaces a container by one built from the edited spec (same validation and ADMIN gate as create). The name is
     * always the old one; the new container is started only if the old one was running; a failure restores the old one
     * (409 CONTAINER_RECREATE_FAILED). Compose-labelled containers: 409 MANAGED_BY_COMPOSE.
     */
    @Auditable(action = "CONTAINER_RECREATE", targetType = "CONTAINER", targetId = "#containerId")
    CreatedResourceDto recreateContainer(UUID hostId, String containerId, CreateContainerRequest request) {
        requireOperate(hostId);
        validateResourceId(containerId);
        ContainerCreateSpec spec = authorizedSpec(request);
        ContainerDetailsDto existing = requireNotComposeManaged(hostId, containerId);
        ContainerCreateSpec named = withName(spec, existing.name());
        return call(hostId, (id, endpoint) -> containers.recreateContainer(id, endpoint, containerId, named));
    }

    /** Creates a new container from the edited spec of an existing one; the original is not touched. */
    @Auditable(action = "CONTAINER_DUPLICATE", targetType = "CONTAINER", targetId = "#containerId")
    CreatedResourceDto duplicateContainer(UUID hostId, String containerId, CreateContainerRequest request) {
        requireOperate(hostId);
        validateResourceId(containerId);
        ContainerCreateSpec spec = authorizedSpec(request);
        requireNotComposeManaged(hostId, containerId);
        return call(hostId, (id, endpoint) -> containers.createContainer(id, endpoint, spec));
    }

    private static ContainerCreateSpec withName(ContainerCreateSpec s, String name) {
        return new ContainerCreateSpec(name, s.image(), s.command(), s.env(), s.ports(), s.mounts(), s.network(),
                s.restartPolicy(), s.privileged(), s.hostNetwork(), s.start());
    }

    private static final long MIN_MEMORY_BYTES = 6L * 1024 * 1024;
    private static final long MAX_MEMORY_BYTES = 1L << 40;

    /** Changes memory, CPU limit and restart policy in place (no restart). 400 INVALID_RESOURCES, 409 RESOURCES_REJECTED. */
    @Auditable(action = "CONTAINER_UPDATE", targetType = "CONTAINER", targetId = "#containerId",
            details = "{memoryBytes: #request?.memoryBytes(), cpus: #request?.cpus(), restartPolicy: #request?.restartPolicy()}")
    void updateContainerResources(UUID hostId, String containerId, UpdateContainerResourcesRequest request) {
        requireOperate(hostId);
        validateResourceId(containerId);
        ContainerResourcesSpec spec = validateResources(request);
        requireNotComposeManaged(hostId, containerId);
        call(hostId, (id, endpoint) -> {
            containers.updateResources(id, endpoint, containerId, spec);
            return null;
        });
    }

    private static ContainerResourcesSpec validateResources(UpdateContainerResourcesRequest r) {
        List<String> v = new java.util.ArrayList<>();
        if (r == null || (r.memoryBytes() == null && r.cpus() == null && r.restartPolicy() == null)) {
            v.add("body: at least one of memoryBytes, cpus, restartPolicy");
            failIfAny("INVALID_RESOURCES", "Invalid resources", v);
        }
        if (r.memoryBytes() != null && r.memoryBytes() != 0
                && (r.memoryBytes() < MIN_MEMORY_BYTES || r.memoryBytes() > MAX_MEMORY_BYTES)) {
            v.add("memoryBytes: 0 (unlimited) or between 6 MiB and 1 TiB");
        }
        if (r.cpus() != null && r.cpus() != 0 && !(r.cpus() >= 0.01 && r.cpus() <= 1024)) {
            v.add("cpus: 0 (unlimited) or between 0.01 and 1024");
        }
        String restart = r.restartPolicy() == null || r.restartPolicy().isBlank() ? null : r.restartPolicy();
        if (restart != null && !RESTART_POLICIES.contains(restart)) {
            v.add("restartPolicy: one of " + new java.util.TreeSet<>(RESTART_POLICIES));
        }
        failIfAny("INVALID_RESOURCES", "Invalid resources", v);
        return new ContainerResourcesSpec(r.memoryBytes(), r.cpus() == null ? null : Math.round(r.cpus() * 1_000_000_000d), restart);
    }

    @Auditable(action = "NETWORK_CREATE", targetType = "NETWORK", targetId = "#request?.name()")
    CreatedResourceDto createNetwork(UUID hostId, CreateNetworkRequest request) {
        requireOperate(hostId);
        List<String> violations = new java.util.ArrayList<>();
        String name = request == null ? null : request.name();
        if (name == null || !RESOURCE_NAME.matcher(name).matches()) {
            violations.add("name: required, letters, digits, _ . - (max 128), must start with a letter or digit");
        }
        String driver = request == null || request.driver() == null || request.driver().isBlank() ? null : request.driver();
        if (driver != null && !NETWORK_DRIVERS.contains(driver)) {
            violations.add("driver: one of " + new java.util.TreeSet<>(NETWORK_DRIVERS));
        }
        String subnet = request == null || request.subnet() == null || request.subnet().isBlank() ? null : request.subnet();
        if (subnet != null && !validCidr(subnet)) {
            violations.add("subnet: not a valid CIDR (e.g. 172.30.0.0/16)");
        }
        failIfAny("INVALID_NETWORK_SPEC", "Invalid network", violations);
        if (driver != null && !"bridge".equals(driver) && !access.isAdmin()) {
            throw new HostException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "Only ADMIN may use the " + driver + " driver");
        }
        NetworkCreateSpec spec = new NetworkCreateSpec(name, driver, Boolean.TRUE.equals(request.internal()), subnet);
        return call(hostId, (id, endpoint) -> networks.createNetwork(id, endpoint, spec));
    }

    @Auditable(action = "VOLUME_CREATE", targetType = "VOLUME", targetId = "#request?.name()")
    CreatedResourceDto createVolume(UUID hostId, CreateVolumeRequest request) {
        requireOperate(hostId);
        List<String> violations = new java.util.ArrayList<>();
        String name = request == null ? null : request.name();
        if (name == null || !RESOURCE_NAME.matcher(name).matches()) {
            violations.add("name: required, letters, digits, _ . - (max 128), must start with a letter or digit");
        }
        String driver = request == null || request.driver() == null || request.driver().isBlank() ? null : request.driver();
        if (driver != null && !"local".equals(driver)) {
            violations.add("driver: only local is supported");
        }
        failIfAny("INVALID_VOLUME_SPEC", "Invalid volume", violations);
        VolumeCreateSpec spec = new VolumeCreateSpec(name, driver);
        return call(hostId, (id, endpoint) -> volumes.createVolume(id, endpoint, spec));
    }

    private ContainerCreateSpec validateContainer(CreateContainerRequest r) {
        List<String> v = new java.util.ArrayList<>();
        if (r == null) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_CONTAINER_SPEC", "Invalid container",
                    List.of("body: required"));
        }
        String name = r.name() == null || r.name().isBlank() ? null : r.name();
        if (name != null && !RESOURCE_NAME.matcher(name).matches()) {
            v.add("name: letters, digits, _ . - (max 128), must start with a letter or digit");
        }
        String image = r.image() == null ? "" : r.image().trim();
        if (image.length() > 255 || !IMAGE_REFERENCE.matcher(image).matches()) {
            v.add("image: not a valid image reference");
        }
        List<String> command = r.command() == null ? List.of() : r.command();
        if (command.size() > MAX_LIST || command.stream().anyMatch(a -> a == null || a.length() > 4096 || a.indexOf('\0') >= 0)) {
            v.add("command: at most " + MAX_LIST + " arguments, no NUL characters");
        }
        Map<String, String> env = r.env() == null ? Map.of() : r.env();
        if (env.size() > MAX_LIST) {
            v.add("env: at most " + MAX_LIST + " variables");
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey() == null || !ENV_KEY.matcher(e.getKey()).matches()) {
                v.add("env: invalid variable name");
            } else if (e.getValue() == null || e.getValue().length() > 8192 || e.getValue().indexOf('\0') >= 0) {
                v.add("env." + e.getKey() + ": value too long or contains NUL");
            }
        }
        List<ContainerCreateSpec.Port> ports = new java.util.ArrayList<>();
        List<ContainerCreateSpec.Port> inPorts = r.ports() == null ? List.of() : r.ports();
        if (inPorts.size() > MAX_LIST) {
            v.add("ports: at most " + MAX_LIST);
        }
        for (ContainerCreateSpec.Port p : inPorts) {
            if (p == null) {
                v.add("ports: entry must not be null");
                continue;
            }
            String protocol = p.protocol() == null || p.protocol().isBlank() ? "tcp" : p.protocol().toLowerCase(java.util.Locale.ROOT);
            if (p.containerPort() == null || p.containerPort() < 1 || p.containerPort() > 65535) {
                v.add("ports: containerPort must be 1-65535");
            } else if (p.hostPort() != null && (p.hostPort() < 1 || p.hostPort() > 65535)) {
                v.add("ports: hostPort must be 1-65535");
            } else if (!protocol.equals("tcp") && !protocol.equals("udp")) {
                v.add("ports: protocol must be tcp or udp");
            } else {
                ports.add(new ContainerCreateSpec.Port(p.hostPort(), p.containerPort(), protocol));
            }
        }
        List<ContainerCreateSpec.Mount> mounts = new java.util.ArrayList<>();
        List<ContainerCreateSpec.Mount> inMounts = r.mounts() == null ? List.of() : r.mounts();
        if (inMounts.size() > MAX_LIST) {
            v.add("mounts: at most " + MAX_LIST);
        }
        for (ContainerCreateSpec.Mount m : inMounts) {
            if (m == null || m.type() == null || !(m.type().equals("BIND") || m.type().equals("VOLUME"))) {
                v.add("mounts: type must be BIND or VOLUME");
            } else if (!validAbsolutePath(m.target())) {
                v.add("mounts: target must be a clean absolute path");
            } else if (m.type().equals("BIND") && !validAbsolutePath(m.source())) {
                v.add("mounts: bind source must be a clean absolute path");
            } else if (m.type().equals("VOLUME") && (m.source() == null || !RESOURCE_NAME.matcher(m.source()).matches())) {
                v.add("mounts: volume source must be a volume name");
            } else {
                mounts.add(new ContainerCreateSpec.Mount(m.type(), m.source(), m.target(), Boolean.TRUE.equals(m.readOnly())));
            }
        }
        String network = r.network() == null || r.network().isBlank() ? null : r.network();
        if (network != null && !RESOURCE_NAME.matcher(network).matches()) {
            v.add("network: invalid network name");
        }
        String restart = r.restartPolicy() == null || r.restartPolicy().isBlank() ? null : r.restartPolicy();
        if (restart != null && !RESTART_POLICIES.contains(restart)) {
            v.add("restartPolicy: one of " + new java.util.TreeSet<>(RESTART_POLICIES));
        }
        boolean hostNetwork = Boolean.TRUE.equals(r.hostNetwork());
        if (network != null && network.equalsIgnoreCase("host")) {
            // "host" is the daemon's host-networking mode, not a user network: same ADMIN gate as hostNetwork
            hostNetwork = true;
            network = null;
        }
        if (hostNetwork && (network != null || !ports.isEmpty())) {
            v.add("hostNetwork: cannot be combined with a network or published ports");
        }
        failIfAny("INVALID_CONTAINER_SPEC", "Invalid container", v);
        return new ContainerCreateSpec(name, image, List.copyOf(command), Map.copyOf(env), List.copyOf(ports),
                List.copyOf(mounts), network, restart, Boolean.TRUE.equals(r.privileged()), hostNetwork,
                Boolean.TRUE.equals(r.start()));
    }

    private static void failIfAny(String code, String message, List<String> violations) {
        if (!violations.isEmpty()) {
            throw new HostException(HttpStatus.BAD_REQUEST, code, message, List.copyOf(violations));
        }
    }

    /**
     * Same rules as {@code PathValidationUtil} (no NUL, backslash, percent-encoding or {@code ..}), but for paths on the
     * Docker host, which this process cannot resolve: absolute and already normalized (no empty, {@code .} segments).
     */
    private static boolean validAbsolutePath(String path) {
        if (path == null || path.length() > 4096 || !path.startsWith("/")
                || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0 || path.matches(".*%[0-9a-fA-F]{2}.*")) {
            return false;
        }
        if (path.equals("/")) {
            return true;
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (String segment : trimmed.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static boolean validCidr(String cidr) {
        var v4 = IPV4_CIDR.matcher(cidr);
        if (v4.matches()) {
            return Integer.parseInt(v4.group(2)) <= 32;
        }
        var v6 = IPV6_CIDR.matcher(cidr);
        return cidr.indexOf(':') >= 0 && v6.matches() && Integer.parseInt(v6.group(1)) <= 128;
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
            if (e.getCode() == DockerErrorCode.NAME_TAKEN) {
                throw new HostException(HttpStatus.CONFLICT, "NAME_TAKEN", "A resource with this name already exists");
            }
            if (e.getCode() == DockerErrorCode.START_FAILED) {
                throw new HostException(HttpStatus.CONFLICT, "CONTAINER_START_FAILED",
                        "The container was created but could not be started (e.g. port in use); it has been removed");
            }
            if (e.getCode() == DockerErrorCode.RECREATE_FAILED) {
                throw new HostException(HttpStatus.CONFLICT, "CONTAINER_RECREATE_FAILED",
                        "The container could not be recreated (e.g. a port is in use); the previous container was kept");
            }
            if (e.getCode() == DockerErrorCode.UPDATE_REJECTED) {
                throw new HostException(HttpStatus.CONFLICT, "RESOURCES_REJECTED",
                        "The Docker daemon refused the new limits (e.g. memory below current usage)");
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
