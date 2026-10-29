package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.SystemScope;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.DockerEventService;
import dev.bethge.shipir.docker.client.ReconnectingEventStream;
import dev.bethge.shipir.docker.dto.DockerEventDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches the Docker event stream of every host and publishes a {@link ContainerProblemEvent} when a container dies
 * with a non-zero exit code (exit 143, a plain SIGTERM stop, is ignored, as is any death preceded by a {@code kill} event with a terminating signal, i.e. a deliberate stop or kill) or reports {@code health_status: unhealthy}.
 *
 * <p>One worker (virtual thread) per host, started by {@link #sync()} and stopped when the host is deleted; a dropped
 * stream is reopened with exponential backoff by the shared {@link ReconnectingEventStream}. The same problem of the same container is reported once per
 * {@code dedupe-window}, so a crash loop yields one event, not one per restart. When a container crashes
 * {@code restart-loop-threshold} times within {@code restart-loop-window}, one further event reports it as a restart loop
 * with the crash count; it fires once per dedupe window, not on every further crash. Off with
 * {@code shipir.host.container-watch.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "shipir.host.container-watch.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ContainerWatchProperties.class)
class ContainerHealthWatcher {

    private static final Logger log = LoggerFactory.getLogger(ContainerHealthWatcher.class);

    static final int SIGTERM_EXIT = 143;
    /** Signals that end the process (INT, QUIT, KILL, TERM); others (HUP, USR1, ...) leave a container running. */
    static final Set<String> TERMINATING_SIGNALS = Set.of("2", "3", "9", "15");
    /** How long a kill event explains the following die event (covers a long {@code docker stop -t}). */
    static final Duration KILL_WINDOW = Duration.ofMinutes(5);

    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final DockerEventService events;
    private final ManagedDeploymentLookup managed;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final ContainerWatchProperties properties;
    private final Map<UUID, ReconnectingEventStream> workers = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastReported = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> crashes = new ConcurrentHashMap<>();
    private final Map<String, Instant> killedAt = new ConcurrentHashMap<>();

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
            workers.computeIfAbsent(host.getId(), id -> {
                ReconnectingEventStream loop = watchLoop(id);
                Thread.ofVirtual().name("container-watch-" + id).start(loop::run);
                return loop;
            });
        }
        Instant now = clock.instant();
        killedAt.entrySet().removeIf(e -> e.getValue().plus(KILL_WINDOW).isBefore(now)
                || current.stream().noneMatch(id -> e.getKey().startsWith(id + "/")));
        workers.keySet().removeIf(id -> {
            if (current.contains(id)) {
                return false;
            }
            workers.get(id).stop();
            killedAt.keySet().removeIf(key -> key.startsWith(id + "/"));
            return true;
        });
    }

    /** Number of remembered terminating kills; for tests. */
    int pendingKills() {
        return killedAt.size();
    }

    @PreDestroy
    void shutdown() {
        workers.values().forEach(ReconnectingEventStream::stop);
        workers.clear();
    }

    /** The shared reconnect loop of one host; this class only decides what an event means. */
    private ReconnectingEventStream watchLoop(UUID hostId) {
        return new ReconnectingEventStream(hostId, events, properties.reconnectInitial(), properties.reconnectMax(),
                new ReconnectingEventStream.Listener() {
                    @Override
                    public Optional<DockerEndpoint> endpoint() {
                        return hosts.findById(hostId).map(endpoints::endpointOf);
                    }

                    @Override
                    public void onEvent(DockerEventDto event) {
                        handle(hostId, event);
                    }
                });
    }

    /** Turns one Docker event into at most one {@link ContainerProblemEvent}; package-private for tests. */
    void handle(UUID hostId, DockerEventDto event) {
        if (event.type() != DockerEventDto.Type.CONTAINER || event.action() == null) {
            return;
        }
        ContainerProblemEvent.Kind kind;
        if ("kill".equals(event.action())) {
            // docker stop/kill and Shipir's stop send this before the die event; the death is deliberate,
            // but only for signals that end the process (a HUP reload must not hide a later crash)
            if (event.signal() == null || !TERMINATING_SIGNALS.contains(event.signal())) {
                return;
            }
            Instant now = clock.instant();
            killedAt.values().removeIf(t -> t.plus(KILL_WINDOW).isBefore(now));
            killedAt.put(hostId + "/" + event.id(), now);
            return;
        }
        if ("die".equals(event.action())) {
            Instant killed = killedAt.remove(hostId + "/" + event.id());
            if (killed != null && !killed.plus(KILL_WINDOW).isBefore(clock.instant())) {
                return;
            }
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
        String key = hostId + "/" + event.id() + "/" + kind;
        Integer loopCount = null;
        if (kind == ContainerProblemEvent.Kind.CRASHED) {
            int count = recordCrash(hostId + "/" + event.id(), now);
            int threshold = properties.restartLoopThreshold();
            if (threshold > 0 && count >= threshold && firstInWindow(key + "/LOOP", now)) {
                loopCount = count;
                lastReported.put(key, now); // the loop report replaces the plain one of this window
            }
        }
        if (loopCount == null && !firstInWindow(key, now)) {
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
                    deploymentName, kind == ContainerProblemEvent.Kind.CRASHED ? event.exitCode() : null, now, loopCount,
                    loopCount == null ? null : properties.restartLoopWindow()));
        } catch (RuntimeException e) {
            log.warn("Could not publish container event of host {}: {}", hostId, e.getClass().getSimpleName());
        }
    }

    /** Remembers a crash and returns how many of this container fall into the restart loop window. */
    private int recordCrash(String containerKey, Instant now) {
        Instant cutoff = now.minus(properties.restartLoopWindow());
        crashes.values().removeIf(d -> {
            synchronized (d) {
                return d.isEmpty() || d.peekLast().isBefore(cutoff);
            }
        });
        Deque<Instant> times = crashes.computeIfAbsent(containerKey, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(now);
            while (times.peekFirst().isBefore(cutoff)) {
                times.removeFirst();
            }
            return times.size();
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
}
