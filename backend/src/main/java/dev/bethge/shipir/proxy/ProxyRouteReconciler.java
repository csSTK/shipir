package dev.bethge.shipir.proxy;

import dev.bethge.shipir.common.security.SystemScope;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.DockerEventService;
import dev.bethge.shipir.docker.client.NetworkService;
import dev.bethge.shipir.docker.client.ReconnectingEventStream;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.DockerEventDto;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Keeps published containers attached to the proxy network. Compose recreates a container on redeploy or image update
 * and the manual connection to {@code shipir-proxy} is lost; this watches the Docker events of every host that has a
 * managed proxy and re-attaches the container of every route whose {@code container_name} matches.
 *
 * <p>Triggers: a container {@code start} event (only that container is looked at, non-routed ones are ignored), every
 * (re)connect of the event stream (full pass over all routes of the host: covers startup and events missed while the
 * stream was down) and the start of the proxy's own container (the config is synced again, the volume is kept but a
 * sync is cheap and heals a lost file). Connecting is idempotent, so repeated events are no-ops.
 *
 * <p>Deliberately built on {@link ReconnectingEventStream} like {@code host/ContainerHealthWatcher}, not on the
 * STOMP-oriented {@code streaming/DockerEventStreamService} (ref-counted by subscribers). Syncs of the proxy config
 * triggered by the proxy's own start events are limited to one per host per {@code proxy-resync-min-interval}.
 *
 * <p>Event threads never block: events only add to a per-host pending set; one drain per host runs on its own virtual
 * thread, under {@link ProxySyncService#exclusive}, so storms collapse into few passes and never overlap with a sync,
 * setup or route change. It runs as the system, not as any user. A failure (daemon down, container gone, proxy network
 * missing because the proxy was removed) is logged by class name only and never ends the stream or the drain.
 */
@Component
@ConditionalOnProperty(name = "shipir.proxy.reconcile.enabled", matchIfMissing = true)
class ProxyRouteReconciler {

    private static final Logger log = LoggerFactory.getLogger(ProxyRouteReconciler.class);
    private static final Duration RECONNECT_INITIAL = Duration.ofSeconds(2);
    private static final Duration RECONNECT_MAX = Duration.ofSeconds(60);

    /** What is waiting to be done for one host. */
    private static final class Pending {
        final Map<String, String> containerIdByName = new LinkedHashMap<>();
        boolean full;
        boolean syncProxy;
    }

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final DockerEventService events;
    private final ProxyInstanceRepository instances;
    private final ProxyRouteRepository routes;
    private final ProxySyncService sync;
    private final NetworkService networks;
    private final ContainerService containers;
    private final Executor executor;
    private final Clock clock;
    private final Duration resyncMinInterval;
    private final Map<UUID, Instant> lastProxySync = new ConcurrentHashMap<>();
    private final Map<UUID, ReconnectingEventStream> workers = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Set<UUID> draining = new HashSet<>();

    @Autowired
    ProxyRouteReconciler(DockerHostRepository hosts, HostEndpointResolver endpoints, DockerEventService events,
                         ProxyInstanceRepository instances, ProxyRouteRepository routes, ProxySyncService sync,
                         NetworkService networks, ContainerService containers,
                         @Value("${shipir.proxy.reconcile.proxy-resync-min-interval:30s}") Duration resyncMinInterval) {
        this(hosts, endpoints, events, instances, routes, sync, networks, containers,
                task -> Thread.ofVirtual().name("proxy-reconcile").start(task), Clock.systemUTC(), resyncMinInterval);
    }

    /** Test seam: a direct executor makes the drain run inline. */
    ProxyRouteReconciler(DockerHostRepository hosts, HostEndpointResolver endpoints, DockerEventService events,
                         ProxyInstanceRepository instances, ProxyRouteRepository routes, ProxySyncService sync,
                         NetworkService networks, ContainerService containers, Executor executor, Clock clock,
                         Duration resyncMinInterval) {
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.events = events;
        this.instances = instances;
        this.routes = routes;
        this.sync = sync;
        this.networks = networks;
        this.containers = containers;
        this.executor = executor;
        this.clock = clock;
        this.resyncMinInterval = resyncMinInterval;
    }

    /** Starts a worker for each host that has a managed proxy and stops the others (proxy removed, host deleted). */
    @Scheduled(fixedDelayString = "${shipir.proxy.reconcile.sync-interval:60s}", initialDelay = 5000)
    void syncWorkers() {
        try {
            Set<UUID> wanted = new HashSet<>();
            SystemScope.run(() -> instances.findAll().forEach(i -> {
                if (hosts.existsById(i.getHostId())) {
                    wanted.add(i.getHostId());
                }
            }));
            for (UUID hostId : wanted) {
                workers.computeIfAbsent(hostId, id -> {
                    ReconnectingEventStream loop = watchLoop(id);
                    Thread.ofVirtual().name("proxy-reconcile-events-" + id).start(loop::run);
                    return loop;
                });
            }
            // hosts that lost their proxy or were deleted: stop the worker and drop every per-host entry, the
            // rate-limit stamp included (it is in lastProxySync even when no worker ever started for the host)
            Set<UUID> gone = new HashSet<>(workers.keySet());
            gone.addAll(lastProxySync.keySet());
            gone.removeAll(wanted);
            for (UUID hostId : gone) {
                ReconnectingEventStream worker = workers.remove(hostId);
                if (worker != null) {
                    worker.stop();
                }
                synchronized (pending) {
                    pending.remove(hostId);
                }
                lastProxySync.remove(hostId);
                sync.forget(hostId);
            }
        } catch (RuntimeException e) {
            log.warn("Could not update the proxy reconcile workers: {}", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        workers.values().forEach(ReconnectingEventStream::stop);
        workers.clear();
    }

    private ReconnectingEventStream watchLoop(UUID hostId) {
        return new ReconnectingEventStream(hostId, events, RECONNECT_INITIAL, RECONNECT_MAX,
                new ReconnectingEventStream.Listener() {
                    @Override
                    public Optional<DockerEndpoint> endpoint() {
                        return hosts.findById(hostId).map(endpoints::endpointOf);
                    }

                    @Override
                    public void onEvent(DockerEventDto event) {
                        handle(hostId, event);
                    }

                    @Override
                    public void onConnected() {
                        requestFullPass(hostId);
                    }
                });
    }

    /** Queues the work one Docker event asks for; never blocks. Package-private for tests. */
    void handle(UUID hostId, DockerEventDto event) {
        if (event.type() != DockerEventDto.Type.CONTAINER || !"start".equals(event.action())) {
            return;
        }
        if (ProxySyncService.PROXY_DEPLOYMENT.equals(event.composeProject())) {
            enqueue(hostId, p -> p.syncProxy = true);
        } else if (event.name() != null && event.id() != null) {
            enqueue(hostId, p -> p.containerIdByName.put(event.name(), event.id()));
        }
    }

    void requestFullPass(UUID hostId) {
        enqueue(hostId, p -> p.full = true);
    }

    private void enqueue(UUID hostId, java.util.function.Consumer<Pending> change) {
        boolean startDrain;
        synchronized (pending) {
            change.accept(pending.computeIfAbsent(hostId, k -> new Pending()));
            startDrain = draining.add(hostId);
        }
        if (startDrain) {
            executor.execute(() -> drain(hostId));
        }
    }

    private void drain(UUID hostId) {
        try {
            while (true) {
                Pending work;
                synchronized (pending) {
                    work = pending.remove(hostId);
                    if (work == null) {
                        draining.remove(hostId);
                        return;
                    }
                }
                try {
                    SystemScope.run(() -> sync.exclusive(hostId, () -> {
                        reconcile(hostId, work);
                        return null;
                    }));
                } catch (RuntimeException e) {
                    log.warn("Proxy reconcile of host {} failed: {}", hostId, e.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException | Error e) {
            synchronized (pending) {
                draining.remove(hostId);
            }
            throw e;
        }
    }

    private void reconcile(UUID hostId, Pending work) {
        if (instances.findByHostId(hostId).isEmpty()) {
            return; // proxy not set up (or removed): nothing to attach to
        }
        if (work.syncProxy && !resyncDue(hostId)) {
            log.debug("Proxy config re-sync of host {} skipped, one ran recently", hostId);
        } else if (work.syncProxy) {
            try {
                sync.sync(hostId);
            } catch (RuntimeException e) {
                log.warn("Proxy config re-sync of host {} failed: {}", hostId, e.getClass().getSimpleName());
            }
            // stamped after the sync: start events caused by its own restart are drained later and fall in the window
            lastProxySync.put(hostId, clock.instant());
        }
        Set<String> routed = new HashSet<>();
        routes.findByHostId(hostId).forEach(r -> routed.add(r.getContainerName()));
        Map<String, String> toAttach = new LinkedHashMap<>();
        work.containerIdByName.forEach((name, id) -> {
            if (routed.contains(name)) {
                toAttach.put(name, id);
            }
        });
        if (routed.isEmpty() || (!work.full && toAttach.isEmpty())) {
            return;
        }
        DockerEndpoint endpoint;
        try {
            endpoint = endpoints.endpointOf(hosts.findById(hostId).orElseThrow());
            if (work.full) {
                List<ContainerDto> all = containers.listContainers(hostId, endpoint);
                for (ContainerDto c : all) {
                    if (routed.contains(c.name())
                            && !ProxySyncService.PROXY_DEPLOYMENT.equals(c.composeProject())) {
                        toAttach.put(c.name(), c.id());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Proxy reconcile of host {} could not reach Docker: {}", hostId, e.getClass().getSimpleName());
            return;
        }
        toAttach.forEach((name, id) -> attach(hostId, endpoint, id));
    }

    /**
     * Rate limit for proxy-triggered syncs. A sync may stop or restart the proxy container, and a crash-looping proxy
     * (e.g. invalid custom config) emits start events by itself; without a limit each would sync again and never
     * converge. Events inside the window are dropped, not deferred: the config lives in a kept volume, so a skipped
     * sync loses nothing, and every user-driven change syncs on its own path.
     */
    private boolean resyncDue(UUID hostId) {
        Instant last = lastProxySync.get(hostId);
        return last == null || !last.plus(resyncMinInterval).isAfter(clock.instant());
    }

    /** One failing container (gone again, network missing) must not stop the others. */
    private void attach(UUID hostId, DockerEndpoint endpoint, String containerId) {
        try {
            networks.connectContainer(hostId, endpoint, ProxySyncService.PROXY_NETWORK, containerId);
        } catch (DockerConnectionException e) {
            if (e.getCode() != DockerErrorCode.NOT_FOUND) {
                log.warn("Could not attach a container of host {} to the proxy network: {}", hostId, e.getCode());
            } else {
                log.debug("Container or proxy network of host {} not found, skipped", hostId);
            }
        } catch (RuntimeException e) {
            log.warn("Could not attach a container of host {} to the proxy network: {}", hostId,
                    e.getClass().getSimpleName());
        }
    }
}
