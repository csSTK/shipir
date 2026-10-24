package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.SystemScope;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.client.DockerEventService;
import dev.bethge.shipir.docker.dto.DockerEventDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches the Docker event stream of every host and publishes a {@link ContainerProblemEvent} when a container dies
 * with a non-zero exit code (exit 143, a plain SIGTERM stop, is ignored) or reports {@code health_status: unhealthy}.
 *
 * <p>One worker (virtual thread) per host, started by {@link #sync()} and stopped when the host is deleted; a dropped
 * stream is reopened with exponential backoff. The same problem of the same container is reported once per
 * {@code dedupe-window}, so a crash loop yields one event, not one per restart. Off with
 * {@code shipir.host.container-watch.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "shipir.host.container-watch.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ContainerWatchProperties.class)
class ContainerHealthWatcher {

    private static final Logger log = LoggerFactory.getLogger(ContainerHealthWatcher.class);

    static final int SIGTERM_EXIT = 143;

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final DockerEventService events;
    private final ManagedDeploymentLookup managed;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final ContainerWatchProperties properties;
    private final Map<UUID, Thread> workers = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastReported = new ConcurrentHashMap<>();

    ContainerHealthWatcher(DockerHostRepository hosts, HostEndpointResolver endpoints, DockerEventService events,
                           ManagedDeploymentLookup managed, ApplicationEventPublisher publisher, Clock clock,
                           ContainerWatchProperties properties) {
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.events = events;
        this.managed = managed;
        this.publisher = publisher;
        this.clock = clock;
        this.properties = properties;
    }

    /** Starts a worker for each host without one and stops the workers of deleted hosts. */
    @Scheduled(fixedDelayString = "${shipir.host.container-watch.sync-interval:60s}", initialDelay = 5000)
    void sync() {
        Set<UUID> current = new HashSet<>();
        for (DockerHost host : hosts.findAll()) {
            current.add(host.getId());
            workers.computeIfAbsent(host.getId(), id ->
                    Thread.ofVirtual().name("container-watch-" + id).start(() -> watch(id)));
        }
        workers.keySet().removeIf(id -> {
            if (current.contains(id)) {
                return false;
            }
            workers.get(id).interrupt();
            return true;
        });
    }

    @PreDestroy
    void shutdown() {
        workers.values().forEach(Thread::interrupt);
        workers.clear();
    }

    private void watch(UUID hostId) {
        Duration delay = properties.reconnectInitial();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                CompletableFuture<Throwable> ended = new CompletableFuture<>();
                Closeable handle = null;
                try {
                    DockerHost host = hosts.findById(hostId).orElse(null);
                    if (host == null) {
                        return;
                    }
                    handle = events.stream(hostId, endpoints.endpointOf(host), event -> handle(hostId, event),
                            ended::complete);
                    delay = properties.reconnectInitial();
                    ended.get();
                } catch (DockerConnectionException e) {
                    // unreachable host: the health check reports it, we just retry
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException | java.util.concurrent.ExecutionException e) {
                    log.warn("Container watch of host {} failed: {}", hostId, e.getClass().getSimpleName());
                } finally {
                    close(handle);
                }
                Thread.sleep(delay);
                delay = delay.multipliedBy(2).compareTo(properties.reconnectMax()) > 0
                        ? properties.reconnectMax() : delay.multipliedBy(2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Turns one Docker event into at most one {@link ContainerProblemEvent}; package-private for tests. */
    void handle(UUID hostId, DockerEventDto event) {
        if (event.type() != DockerEventDto.Type.CONTAINER || event.action() == null) {
            return;
        }
        ContainerProblemEvent.Kind kind;
        if ("die".equals(event.action())) {
            Integer exit = event.exitCode();
            if (exit == null || exit == 0 || exit == SIGTERM_EXIT) {
                return;
            }
            kind = ContainerProblemEvent.Kind.CRASHED;
        } else if ("health_status: unhealthy".equals(event.action())) {
            kind = ContainerProblemEvent.Kind.UNHEALTHY;
        } else {
            return;
        }
        Instant now = clock.instant();
        if (!firstInWindow(hostId + "/" + event.id() + "/" + kind, now)) {
            return;
        }
        try {
            String hostName = hosts.findById(hostId).map(DockerHost::getName).orElse(hostId.toString());
            UUID deploymentId = null;
            String deploymentName = null;
            if (event.composeProject() != null) {
                deploymentId = SystemScope.call(() -> managed.managedDeploymentIds(hostId)).get(event.composeProject());
                deploymentName = deploymentId == null ? null : event.composeProject();
            }
            String name = event.name() != null ? event.name() : shortId(event.id());
            publisher.publishEvent(new ContainerProblemEvent(kind, hostId, hostName, event.id(), name, deploymentId,
                    deploymentName, kind == ContainerProblemEvent.Kind.CRASHED ? event.exitCode() : null, now));
        } catch (RuntimeException e) {
            log.warn("Could not publish container event of host {}: {}", hostId, e.getClass().getSimpleName());
        }
    }

    /** True (and remembers {@code now}) unless the key was reported within the dedupe window. */
    private boolean firstInWindow(String key, Instant now) {
        lastReported.values().removeIf(t -> t.plus(properties.dedupeWindow()).isBefore(now));
        return lastReported.putIfAbsent(key, now) == null;
    }

    private static String shortId(String id) {
        return id == null ? "unknown" : id.substring(0, Math.min(12, id.length()));
    }

    private static void close(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            // best effort, the stream is gone either way
        }
    }
}
