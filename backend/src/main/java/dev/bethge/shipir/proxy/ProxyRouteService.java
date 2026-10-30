package dev.bethge.shipir.proxy;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.host.ManagedDeploymentLookup;
import dev.bethge.shipir.permission.CurrentAccess;
import dev.bethge.shipir.proxy.dto.ProxyRouteCandidate;
import dev.bethge.shipir.proxy.dto.ProxyRouteRequest;
import dev.bethge.shipir.proxy.dto.ProxyRouteView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Publishes containers through the managed proxy: validates a route, connects the container to the proxy network,
 * stores the route and syncs the proxy. Reads are open to every role that sees the host; on a host visible only
 * through deployment grants a user sees (and may publish) only containers of the managed deployments they can see
 * (respectively operate). Writes need OPERATE on the host or on the container's managed deployment, the same rule as
 * a container's lifecycle actions extended to deployment grants, and run under the host's proxy lock.
 *
 * <p>Basic auth passwords arrive in plain text once and are stored bcrypt-hashed; they are never returned, audited
 * or logged.
 */
@Service
public class ProxyRouteService {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(10);
    private static final int MAX_PORT_OPTIONS = 50;

    private final ProxyInstanceRepository instances;
    private final ProxyRouteRepository routes;
    private final ProxyCertificateRepository certificates;
    private final ProxyRouteValidator validator;
    private final ProxySyncService sync;
    private final ProxyHostAccess access;
    private final CurrentAccess currentAccess;
    private final ManagedDeploymentLookup managedDeployments;
    private final ContainerService containers;
    private final NetworkService networks;

    ProxyRouteService(ProxyInstanceRepository instances, ProxyRouteRepository routes,
                      ProxyCertificateRepository certificates, ProxyRouteValidator validator, ProxySyncService sync,
                      ProxyHostAccess access, CurrentAccess currentAccess, ManagedDeploymentLookup managedDeployments,
                      ContainerService containers, NetworkService networks) {
        this.instances = instances;
        this.routes = routes;
        this.certificates = certificates;
        this.validator = validator;
        this.sync = sync;
        this.access = access;
        this.currentAccess = currentAccess;
        this.managedDeployments = managedDeployments;
        this.containers = containers;
        this.networks = networks;
    }

    /** Routes of the host; with deployment grants only: those whose container belongs to a visible deployment. */
    public List<ProxyRouteView> list(UUID hostId) {
        access.requireView(hostId);
        DockerEndpoint endpoint = access.endpoint(hostId);
        Map<String, ContainerDto> byName = containersByName(hostId, endpoint);
        ProxyInstance instance = instances.findByHostId(hostId).orElse(null);
        CertMode defaultMode = instance == null ? null : instance.getDefaultCertMode();
        boolean full = currentAccess.hostFullyVisible(hostId);
        Map<String, UUID> managed = full ? Map.of() : managedDeployments.managedDeploymentIds(hostId);
        return routes.findByHostId(hostId).stream()
                .filter(r -> full || visibleContainer(hostId, managed, byName.get(r.getContainerName())))
                .sorted(java.util.Comparator.comparing(ProxyRoute::getHostname))
                .map(r -> view(r, defaultMode, byName.get(r.getContainerName())))
                .toList();
    }

    /** Containers that can be published (visible ones only, the proxy's own excluded) with their exposed ports. */
    public List<ProxyRouteCandidate> candidates(UUID hostId) {
        access.requireView(hostId);
        DockerEndpoint endpoint = access.endpoint(hostId);
        boolean full = currentAccess.hostFullyVisible(hostId);
        Map<String, UUID> managed = full ? Map.of() : managedDeployments.managedDeploymentIds(hostId);
        Set<String> published = new HashSet<>();
        routes.findByHostId(hostId).forEach(r -> published.add(r.getContainerName()));
        return call(() -> containers.listContainers(hostId, endpoint)).stream()
                .filter(c -> !ProxySyncService.PROXY_DEPLOYMENT.equals(c.composeProject()))
                .filter(c -> full || visibleContainer(hostId, managed, c))
                .sorted(java.util.Comparator.comparing(ContainerDto::name))
                .map(c -> {
                    List<Integer> ports = exposedPorts(hostId, endpoint, c);
                    return new ProxyRouteCandidate(c.name(), c.image(), c.state(), ports,
                            ports.size() == 1 ? ports.get(0) : null, published.contains(c.name()));
                })
                .toList();
    }

    @Auditable(action = "PROXY_ROUTE_CREATE", targetType = "PROXY_ROUTE", targetId = "#hostId",
            details = "{'hostname': #request.hostname(), 'containerName': #request.containerName()}")
    public ProxyRouteView publish(UUID hostId, ProxyRouteRequest request) {
        access.requireView(hostId);
        return sync.exclusive(hostId, () -> publishLocked(hostId, request));
    }

    private ProxyRouteView publishLocked(UUID hostId, ProxyRouteRequest r) {
        ProxyInstance instance = requireInstance(hostId);
        DockerEndpoint endpoint = access.endpoint(hostId);
        ContainerDto container = requireOperableContainer(hostId, endpoint, r.containerName());
        int port = r.port() != null ? r.port() : detectPort(hostId, endpoint, container);
        CertMode mode = r.certMode() != null ? r.certMode() : instance.getDefaultCertMode();
        Map<String, String> hashes = hashUsers(r.basicAuth(), Map.of());
        validate(hostId, instance, r, container.name(), port, mode, hashes, otherNames(hostId, null));

        connect(hostId, endpoint, container);
        ProxyRoute route = ProxyRoute.create(hostId, r.hostname(), r.aliases(), container.name(), port, scheme(r),
                r.pathPrefix(), r.entrypoint(), mode, r.certificateId(), redirect(r), hashes, r.responseHeaders());
        route = save(route);
        try {
            sync.sync(hostId);
        } catch (RuntimeException e) {
            rollbackPublish(hostId, endpoint, route, container);
            throw e;
        }
        return view(route, instance.getDefaultCertMode(), container);
    }

    /** A failed sync must not leave a stored route behind (a retry would hit ROUTE_HOST_TAKEN); best effort. */
    private void rollbackPublish(UUID hostId, DockerEndpoint endpoint, ProxyRoute route, ContainerDto container) {
        try {
            routes.delete(route);
            detachIfUnused(hostId, endpoint, container.name(), container);
        } catch (RuntimeException ignored) {
            // the sync failure is the error to report
        }
    }

    @Auditable(action = "PROXY_ROUTE_UPDATE", targetType = "PROXY_ROUTE", targetId = "#routeId",
            details = "{'hostname': #request.hostname()}")
    public ProxyRouteView update(UUID hostId, UUID routeId, ProxyRouteRequest request) {
        access.requireView(hostId);
        return sync.exclusive(hostId, () -> updateLocked(hostId, routeId, request));
    }

    private ProxyRouteView updateLocked(UUID hostId, UUID routeId, ProxyRouteRequest r) {
        ProxyInstance instance = requireInstance(hostId);
        ProxyRoute route = requireRoute(hostId, routeId);
        DockerEndpoint endpoint = access.endpoint(hostId);
        String oldContainer = route.getContainerName();
        Map<String, ContainerDto> byName = containersByName(hostId, endpoint);
        requireOperable(hostId, byName.get(oldContainer));

        String name = r.containerName() == null ? oldContainer : r.containerName();
        boolean moved = !name.equals(oldContainer);
        ContainerDto container = moved ? requireOperableContainer(hostId, endpoint, name) : byName.get(oldContainer);
        if (container == null) {
            throw containerNotFound();
        }
        int port = r.port() != null ? r.port() : moved ? detectPort(hostId, endpoint, container) : route.getTargetPort();
        CertMode mode = r.certMode() != null ? r.certMode() : route.getCertMode();
        Map<String, String> hashes = r.basicAuth() == null ? route.getBasicAuthUsers() == null ? Map.of() : route.getBasicAuthUsers()
                : hashUsers(r.basicAuth(), route.getBasicAuthUsers() == null ? Map.of() : route.getBasicAuthUsers());
        Set<String> others = otherNames(hostId, routeId);
        validate(hostId, instance, r, container.name(), port, mode, hashes, others);

        if (moved) {
            connect(hostId, endpoint, container);
        }
        route.apply(r.hostname(), r.aliases(), container.name(), port, scheme(r), r.pathPrefix(), r.entrypoint(), mode,
                r.certificateId(), redirect(r), hashes, r.responseHeaders());
        route = save(route);
        sync.sync(hostId);
        if (moved) {
            detachIfUnused(hostId, endpoint, oldContainer, byName.get(oldContainer));
        }
        return view(route, instance.getDefaultCertMode(), container);
    }

    @Auditable(action = "PROXY_ROUTE_DELETE", targetType = "PROXY_ROUTE", targetId = "#routeId")
    public void unpublish(UUID hostId, UUID routeId) {
        access.requireView(hostId);
        sync.exclusive(hostId, () -> {
            unpublishLocked(hostId, routeId);
            return null;
        });
    }

    private void unpublishLocked(UUID hostId, UUID routeId) {
        ProxyRoute route = requireRoute(hostId, routeId);
        DockerEndpoint endpoint = access.endpoint(hostId);
        ContainerDto container = containersByName(hostId, endpoint).get(route.getContainerName());
        requireOperable(hostId, container);
        routes.delete(route);
        // sync first: a failing detach must not leave the proxy serving the removed route
        sync.sync(hostId);
        detachIfUnused(hostId, endpoint, route.getContainerName(), container);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private ProxyInstance requireInstance(UUID hostId) {
        return instances.findByHostId(hostId).orElseThrow(() -> new ProxyException(HttpStatus.CONFLICT,
                "PROXY_NOT_SET_UP", "This environment has no managed proxy yet"));
    }

    private ProxyRoute requireRoute(UUID hostId, UUID routeId) {
        return routes.findById(routeId).filter(r -> r.getHostId().equals(hostId)).orElseThrow(
                () -> new ProxyException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "Route not found"));
    }

    private static ProxyException containerNotFound() {
        return new ProxyException(HttpStatus.NOT_FOUND, "CONTAINER_NOT_FOUND", "Container not found");
    }

    /** The named container, or 404 if it does not exist or is invisible; 403 if visible but not operable. */
    private ContainerDto requireOperableContainer(UUID hostId, DockerEndpoint endpoint, String name) {
        if (name == null || name.isBlank()) {
            throw new ProxyException(HttpStatus.BAD_REQUEST, "INVALID_CONTAINER", "Invalid container name");
        }
        ContainerDto container = containersByName(hostId, endpoint).get(name);
        if (container == null) {
            throw containerNotFound();
        }
        requireOperable(hostId, container);
        return container;
    }

    /**
     * OPERATE on the host, or on the container's managed deployment. A container that is invisible to the user (or
     * already gone, when the user lacks host-level OPERATE) is a 404, a visible but not operable one a 403.
     */
    private void requireOperable(UUID hostId, ContainerDto container) {
        if (ProxySyncService.PROXY_DEPLOYMENT.equals(container == null ? null : container.composeProject())) {
            throw new ProxyException(HttpStatus.BAD_REQUEST, "INVALID_CONTAINER", "The proxy itself cannot be published");
        }
        if (currentAccess.canOperate(hostId)) {
            return;
        }
        Map<String, UUID> managed = managedDeployments.managedDeploymentIds(hostId);
        UUID deployment = deploymentOf(managed, container);
        if (deployment != null && currentAccess.canOperate(hostId, deployment)) {
            return;
        }
        if (container != null && visibleContainer(hostId, managed, container)) {
            throw new ProxyException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
        }
        throw container == null && currentAccess.hostFullyVisible(hostId)
                ? new ProxyException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied") : containerNotFound();
    }

    private boolean visibleContainer(UUID hostId, Map<String, UUID> managed, ContainerDto container) {
        if (currentAccess.hostFullyVisible(hostId)) {
            return true;
        }
        UUID deployment = deploymentOf(managed, container);
        return deployment != null && currentAccess.canView(hostId, deployment);
    }

    private static UUID deploymentOf(Map<String, UUID> managed, ContainerDto container) {
        return container == null || container.composeProject() == null ? null : managed.get(container.composeProject());
    }

    private Map<String, ContainerDto> containersByName(UUID hostId, DockerEndpoint endpoint) {
        Map<String, ContainerDto> byName = new LinkedHashMap<>();
        call(() -> containers.listContainers(hostId, endpoint)).forEach(c -> byName.put(c.name(), c));
        return byName;
    }

    private List<Integer> exposedPorts(UUID hostId, DockerEndpoint endpoint, ContainerDto container) {
        try {
            ContainerDetailsDto details = containers.inspect(hostId, endpoint, container.id());
            Set<Integer> ports = new TreeSet<>();
            for (ContainerDetailsDto.Port p : details.ports()) {
                if ("tcp".equalsIgnoreCase(p.protocol())) {
                    ports.add(p.containerPort());
                }
            }
            return List.copyOf(ports);
        } catch (DockerConnectionException e) {
            if (e.getCode() == DockerErrorCode.NOT_FOUND) {
                return List.of();
            }
            throw ProxyHostAccess.dockerFailure(e);
        }
    }

    /** The single exposed TCP port; 400 PORT_REQUIRED listing the options when there is none or several. */
    private int detectPort(UUID hostId, DockerEndpoint endpoint, ContainerDto container) {
        List<Integer> ports = exposedPorts(hostId, endpoint, container);
        if (ports.size() == 1) {
            return ports.get(0);
        }
        throw new ProxyException(HttpStatus.BAD_REQUEST, "PORT_REQUIRED",
                ports.isEmpty() ? "The container exposes no port; enter the port to route to"
                        : "The container exposes several ports; choose one",
                ports.stream().limit(MAX_PORT_OPTIONS).map(String::valueOf).toList());
    }

    private void validate(UUID hostId, ProxyInstance instance, ProxyRouteRequest r, String container, int port,
                          CertMode mode, Map<String, String> hashes, Set<String> takenNames) {
        ProxyCertificate certificate = null;
        if (r.certificateId() != null) {
            certificate = certificates.findById(r.certificateId()).orElseThrow(
                    () -> new ProxyException(HttpStatus.NOT_FOUND, "CERT_NOT_FOUND", "Certificate not found on this host"));
        }
        validator.validate(hostId, instance.getType(), RouteInput.builder().hostname(r.hostname())
                .aliases(r.aliases() == null ? List.of() : r.aliases()).containerName(container).targetPort(port)
                .backendScheme(scheme(r)).pathPrefix(r.pathPrefix()).entrypoint(r.entrypoint()).certMode(mode)
                .basicAuthUsers(hashes).responseHeaders(r.responseHeaders()).build(), takenNames, certificate);
    }

    private Set<String> otherNames(UUID hostId, UUID excludeRouteId) {
        Set<String> names = new HashSet<>();
        routes.findByHostId(hostId).stream().filter(x -> !x.getId().equals(excludeRouteId))
                .forEach(x -> names.addAll(x.allNames()));
        return names;
    }

    private static BackendScheme scheme(ProxyRouteRequest r) {
        return r.backendScheme() == null ? BackendScheme.HTTP : r.backendScheme();
    }

    private static boolean redirect(ProxyRouteRequest r) {
        return r.redirectHttps() == null || r.redirectHttps();
    }

    /** New passwords are hashed; a user without a password keeps the hash from {@code existing} or is rejected. */
    private static Map<String, String> hashUsers(Map<String, String> users, Map<String, String> existing) {
        Map<String, String> hashes = new LinkedHashMap<>();
        if (users == null) {
            return hashes;
        }
        users.forEach((user, password) -> {
            if (password == null || password.isEmpty()) {
                String kept = existing.get(user);
                if (kept == null) {
                    throw new ProxyException(HttpStatus.BAD_REQUEST, "INVALID_BASIC_AUTH", "A password is required for a new user");
                }
                hashes.put(user, kept);
            } else if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
                throw new ProxyException(HttpStatus.BAD_REQUEST, "INVALID_BASIC_AUTH", "Passwords can be at most 72 bytes");
            } else {
                hashes.put(user, BCRYPT.encode(password));
            }
        });
        return hashes;
    }

    private void connect(UUID hostId, DockerEndpoint endpoint, ContainerDto container) {
        try {
            networks.connectContainer(hostId, endpoint, ProxySyncService.PROXY_NETWORK, container.id());
        } catch (DockerConnectionException e) {
            if (e.getCode() == DockerErrorCode.NOT_FOUND) {
                throw new ProxyException(HttpStatus.BAD_GATEWAY, "PROXY_ATTACH_FAILED",
                        "The container could not be attached to the network " + ProxySyncService.PROXY_NETWORK
                                + " (is the proxy set up and the container still there?)");
            }
            throw ProxyHostAccess.dockerFailure(e);
        }
    }

    /** Leaves the proxy network when no route targets the container any more; a vanished container is fine. */
    private void detachIfUnused(UUID hostId, DockerEndpoint endpoint, String containerName, ContainerDto container) {
        if (container == null || routes.findByHostId(hostId).stream().anyMatch(r -> r.getContainerName().equals(containerName))) {
            return;
        }
        try {
            networks.disconnectContainer(hostId, endpoint, ProxySyncService.PROXY_NETWORK, container.id());
        } catch (DockerConnectionException e) {
            if (e.getCode() != DockerErrorCode.NOT_FOUND) {
                throw ProxyHostAccess.dockerFailure(e);
            }
        }
    }

    private ProxyRoute save(ProxyRoute route) {
        try {
            return routes.saveAndFlush(route);
        } catch (DataIntegrityViolationException e) {
            throw new ProxyException(HttpStatus.CONFLICT, "ROUTE_HOST_TAKEN", "Name already used by a route on this host");
        }
    }

    private static <T> T call(java.util.function.Supplier<T> docker) {
        try {
            return docker.get();
        } catch (DockerConnectionException e) {
            if (e.getCode() == DockerErrorCode.NOT_FOUND) {
                throw containerNotFound();
            }
            throw ProxyHostAccess.dockerFailure(e);
        }
    }

    private static ProxyRouteView view(ProxyRoute r, CertMode defaultMode, ContainerDto container) {
        boolean advanced = !r.getAliases().isEmpty() || r.getPathPrefix() != null || r.getEntrypoint() != null
                || r.getCertificateId() != null || !r.isRedirectHttps() || r.getBackendScheme() != BackendScheme.HTTP
                || r.getBasicAuthUsers() != null || r.getResponseHeaders() != null
                || (defaultMode != null && r.getCertMode() != defaultMode);
        List<String> users = r.getBasicAuthUsers() == null ? List.of() : r.getBasicAuthUsers().keySet().stream().sorted().toList();
        return new ProxyRouteView(r.getId(), r.getHostname(), r.getAliases(), r.getContainerName(), r.getTargetPort(),
                r.getBackendScheme(), r.getPathPrefix(), r.getEntrypoint(), r.getCertMode(), r.getCertificateId(),
                r.isRedirectHttps(), users, r.getResponseHeaders(), advanced,
                container == null ? null : container.state(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
