package dev.bethge.shipir.streaming;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ContainerStatsService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ContainerStatsDto;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Live container resource usage on {@code /topic/hosts/{hostId}/stats}.
 *
 * <p>Subscriptions are reference counted per host: the first SUBSCRIBE starts one worker (virtual thread) that
 * every {@code shipir.streaming.stats.interval} samples all running containers of the host and sends one frame
 * to each subscription, the last UNSUBSCRIBE or disconnect stops it. Each frame only holds containers the
 * subscriber may see (whole host, or the containers of visible managed deployments); visibility is re-read per
 * frame so revoked grants take effect at once. No history is kept.
 */
@Service
@EnableConfigurationProperties(ContainerStatsProperties.class)
public class ContainerStatsStreamService {

    private static final Logger log = LoggerFactory.getLogger(ContainerStatsStreamService.class);

    static final String TOPIC_PREFIX = "/topic/hosts/";
    private static final Pattern TOPIC =
            Pattern.compile("^" + Pattern.quote(TOPIC_PREFIX) + "([0-9a-fA-F-]{36})/stats$");

    private record Subscription(String sessionId, String subscriptionId, String username) {
    }

    private record Sampled(String containerId, String project, ContainerStatsDto stats) {
    }

    private static final class HostStream {
        final UUID hostId;
        final List<Subscription> subscribers = new ArrayList<>();
        long nextSeq = 1;
        boolean closed;
        Thread worker;

        HostStream(UUID hostId) {
            this.hostId = hostId;
        }
    }

    private final Map<UUID, HostStream> streams = new HashMap<>();
    private final ContainerService containers;
    private final ContainerStatsService stats;
    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final StompFrameSender sender;
    private final ContainerStatsProperties properties;
    private final StreamAccess access;

    ContainerStatsStreamService(ContainerService containers, ContainerStatsService stats, DockerHostRepository hosts,
                                HostEndpointResolver endpoints, StompFrameSender sender,
                                ContainerStatsProperties properties, StreamAccess access) {
        this.containers = containers;
        this.stats = stats;
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.sender = sender;
        this.properties = properties;
        this.access = access;
    }

    @PreDestroy
    void shutdown() {
        List<HostStream> all;
        synchronized (streams) {
            all = new ArrayList<>(streams.values());
        }
        all.forEach(this::stop);
    }

    /** Host id of a stats topic; null if the destination is not one. */
    static UUID parse(String destination) {
        var m = TOPIC.matcher(destination);
        if (!m.matches()) {
            return null;
        }
        try {
            return UUID.fromString(m.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String destination(UUID hostId) {
        return TOPIC_PREFIX + hostId + "/stats";
    }

    /** Registers a subscription and starts the sampling worker for the first subscriber of the host. */
    void subscribe(UUID hostId, String username, String sessionId, String subscriptionId) {
        while (true) {
            HostStream stream;
            boolean first;
            synchronized (streams) {
                stream = streams.get(hostId);
                first = stream == null;
                if (first) {
                    stream = new HostStream(hostId);
                    streams.put(hostId, stream);
                }
            }
            synchronized (stream) {
                if (stream.closed) {
                    continue; // released concurrently, start over with a fresh stream
                }
                stream.subscribers.add(new Subscription(sessionId, subscriptionId, username));
                if (first) {
                    HostStream started = stream;
                    stream.worker = Thread.ofVirtual().name("container-stats-" + hostId).unstarted(() -> run(started));
                    stream.worker.start();
                }
                return;
            }
        }
    }

    void unsubscribe(String sessionId, String subscriptionId) {
        List<HostStream> all;
        synchronized (streams) {
            all = new ArrayList<>(streams.values());
        }
        for (HostStream stream : all) {
            boolean empty;
            synchronized (stream) {
                stream.subscribers.removeIf(s -> s.sessionId.equals(sessionId)
                        && (subscriptionId == null || s.subscriptionId.equals(subscriptionId)));
                empty = stream.subscribers.isEmpty();
            }
            if (empty) {
                release(stream);
            }
        }
    }

    int subscriberCount(UUID hostId) {
        HostStream stream;
        synchronized (streams) {
            stream = streams.get(hostId);
        }
        if (stream == null) {
            return 0;
        }
        synchronized (stream) {
            return stream.subscribers.size();
        }
    }

    int activeStreams() {
        synchronized (streams) {
            return streams.size();
        }
    }

    private void release(HostStream stream) {
        synchronized (streams) {
            synchronized (stream) {
                if (!stream.subscribers.isEmpty() || stream.closed) {
                    return;
                }
            }
            streams.remove(stream.hostId, stream);
        }
        stop(stream);
    }

    private void stop(HostStream stream) {
        synchronized (stream) {
            stream.closed = true;
            stream.notifyAll(); // wakes a sleeping worker; never interrupt, it could be inside a DB call
        }
    }

    /** Worker: sample, send, wait, repeat until closed. */
    private void run(HostStream stream) {
        try {
            while (!isClosed(stream)) {
                var host = hosts.findById(stream.hostId).orElse(null);
                if (host == null) {
                    broadcast(stream, List.of(), DockerErrorCode.NOT_FOUND.name());
                    drop(stream);
                    return; // host is gone, retrying is pointless
                }
                try {
                    var endpoint = endpoints.endpointOf(host);
                    broadcast(stream, sample(stream.hostId, endpoint, containers.listContainers(stream.hostId, endpoint)),
                            null);
                } catch (DockerConnectionException e) {
                    broadcast(stream, List.of(), e.getCode().name());
                } catch (RuntimeException e) {
                    // e.g. undecryptable TLS material or missing encryption key: no detail to the client
                    log.warn("Container stats of host {} failed: {}", stream.hostId, e.getClass().getSimpleName());
                    broadcast(stream, List.of(), "UNAVAILABLE");
                }
                pause(stream);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sleeps one interval; woken early (without interrupt) when the stream is closed. */
    private void pause(HostStream stream) throws InterruptedException {
        long deadline = System.nanoTime() + properties.interval().toNanos();
        synchronized (stream) {
            long remaining;
            while (!stream.closed && (remaining = deadline - System.nanoTime()) > 0) {
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(stream, remaining);
            }
        }
    }

    private List<Sampled> sample(UUID hostId, dev.bethge.shipir.docker.DockerEndpoint endpoint,
                                 List<ContainerDto> all) throws InterruptedException {
        var running = all.stream().filter(c -> "running".equalsIgnoreCase(c.state()))
                .limit(properties.maxContainers()).toList();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Sampled>> futures = new ArrayList<>();
            for (ContainerDto c : running) {
                futures.add(pool.submit(() -> {
                    try {
                        return new Sampled(c.id(), c.composeProject(), stats.snapshot(hostId, endpoint, c.id()));
                    } catch (DockerConnectionException e) {
                        return null; // container vanished or hiccup: skip it this round
                    }
                }));
            }
            List<Sampled> result = new ArrayList<>();
            for (Future<Sampled> f : futures) {
                try {
                    Sampled s = f.get();
                    if (s != null) {
                        result.add(s);
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    log.debug("Container stats sample failed: {}", e.getCause().getClass().getSimpleName());
                }
            }
            return result;
        }
    }

    private boolean isClosed(HostStream stream) {
        synchronized (stream) {
            return stream.closed;
        }
    }

    /** Removes a stream whose worker ended for good so a later SUBSCRIBE starts a fresh one. */
    private void drop(HostStream stream) {
        synchronized (streams) {
            streams.remove(stream.hostId, stream);
        }
        synchronized (stream) {
            stream.closed = true;
            stream.notifyAll();
        }
    }

    /** Sends one frame to every subscription, each with the samples its user may see. Only the worker calls this. */
    private void broadcast(HostStream stream, List<Sampled> sampled, String code) {
        List<Subscription> subs;
        long seq;
        synchronized (stream) {
            if (stream.closed && code == null) {
                return;
            }
            subs = List.copyOf(stream.subscribers);
            seq = stream.nextSeq++;
        }
        Map<String, Set<String>> projectsByUser = new HashMap<>();
        for (Subscription sub : subs) {
            List<ContainerStatsMessage.Sample> samples = List.of();
            if (code == null) {
                Set<String> visible = projectsByUser.computeIfAbsent(sub.username,
                        u -> access.visibleProjects(u, stream.hostId).orElse(null));
                samples = sampled.stream()
                        .filter(s -> visible == null || (s.project != null && visible.contains(s.project)))
                        .map(ContainerStatsStreamService::toSample).toList();
            }
            sender.send(sub.sessionId, sub.subscriptionId, destination(stream.hostId),
                    new ContainerStatsMessage(seq, samples, code));
        }
    }

    private static ContainerStatsMessage.Sample toSample(Sampled s) {
        ContainerStatsDto d = s.stats;
        return new ContainerStatsMessage.Sample(s.containerId, d.cpuPercent(), d.memoryUsageBytes(),
                d.memoryLimitBytes(), d.networkRxBytes(), d.networkTxBytes(), d.blockReadBytes(),
                d.blockWriteBytes());
    }
}
