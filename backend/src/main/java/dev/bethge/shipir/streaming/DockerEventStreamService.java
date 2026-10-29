package dev.bethge.shipir.streaming;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.DockerEventService;
import dev.bethge.shipir.docker.dto.DockerEventDto;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Live Docker events on {@code /topic/hosts/{hostId}/events}.
 *
 * <p>Subscriptions are reference counted per host: the first SUBSCRIBE starts one worker (virtual thread) that
 * opens the daemon's event stream, later subscribers share it, the last UNSUBSCRIBE or disconnect stops it.
 * If the stream drops or the host is unreachable, a STATUS DISCONNECTED frame is sent once per distinct
 * failure and the worker reconnects with exponential backoff. Events are debounced into EVENT frames, sent
 * to each subscription under the stream lock so order is kept. No history is kept; a late subscriber gets
 * the latest STATUS frame.
 */
@Service
@EnableConfigurationProperties(DockerEventProperties.class)
public class DockerEventStreamService {

    private static final Logger log = LoggerFactory.getLogger(DockerEventStreamService.class);

    static final String TOPIC_PREFIX = "/topic/hosts/";
    private static final Pattern TOPIC =
            Pattern.compile("^" + Pattern.quote(TOPIC_PREFIX) + "([0-9a-fA-F-]{36})/events$");

    private record Subscription(String sessionId, String subscriptionId, String username) {
    }

    private record EventKey(DockerEventDto.Type type, String action, String id) {
    }

    private static final class HostStream {
        final UUID hostId;
        final List<Subscription> subscribers = new ArrayList<>();
        final Map<EventKey, DockerEventDto> pending = new LinkedHashMap<>();
        int omitted;
        long nextSeq = 1;
        boolean closed;
        Closeable current;
        ScheduledFuture<?> flush;
        DockerEventMessage lastStatus;

        HostStream(UUID hostId) {
            this.hostId = hostId;
        }
    }

    private final Map<UUID, HostStream> streams = new HashMap<>();
    private final DockerEventService events;
    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final StompFrameSender sender;
    private final DockerEventProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("docker-event-flush").factory());

    DockerEventStreamService(DockerEventService events, DockerHostRepository hosts, HostEndpointResolver endpoints,
                             StompFrameSender sender, DockerEventProperties properties) {
        this.events = events;
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.sender = sender;
        this.properties = properties;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        List<HostStream> all;
        synchronized (streams) {
            all = new ArrayList<>(streams.values());
        }
        all.forEach(this::stop);
    }

    /** Host id of an events topic; null if the destination is not one. */
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
        return TOPIC_PREFIX + hostId + "/events";
    }

    /**
     * Permission hook: may this user follow the events of this host? Today every authenticated user may
     * (read access); the permissions epic narrows it per host here.
     */
    boolean mayFollow(String username, UUID hostId) {
        return true;
    }

    /** Registers a subscription and starts the event worker for the first subscriber of the host. */
    void subscribe(UUID hostId, String username, String sessionId, String subscriptionId) {
        HostStream stream;
        boolean first;
        while (true) {
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
                Subscription sub = new Subscription(sessionId, subscriptionId, username);
                stream.subscribers.add(sub);
                if (stream.lastStatus != null) {
                    send(stream, sub, stream.lastStatus);
                }
                if (first) {
                    HostStream started = stream;
                    Thread.ofVirtual().name("docker-events-" + hostId).start(() -> run(started));
                }
                break;
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

    /**
     * Drops the user's subscriptions if they may no longer follow the host (role, grant or account changed after
     * SUBSCRIBE). Nothing is sent to them any more; the STOMP connection itself stays.
     */
    void revalidate(String username, Predicate<UUID> hostStillAllowed) {
        List<HostStream> all;
        synchronized (streams) {
            all = new ArrayList<>(streams.values());
        }
        for (HostStream stream : all) {
            List<Subscription> revoked;
            synchronized (stream) {
                revoked = stream.subscribers.stream().filter(s -> username.equals(s.username)).toList();
            }
            // the predicate costs DB queries: only ask for hosts this user is subscribed to
            if (revoked.isEmpty() || hostStillAllowed.test(stream.hostId)) {
                continue;
            }
            revoked.forEach(s -> unsubscribe(s.sessionId, s.subscriptionId));
        }
    }

    /** Drops every subscription on a deleted host, whoever holds it. */
    void dropHost(UUID hostId) {
        List<HostStream> all;
        synchronized (streams) {
            all = new ArrayList<>(streams.values());
        }
        for (HostStream stream : all) {
            if (!stream.hostId.equals(hostId)) {
                continue;
            }
            List<Subscription> dropped;
            synchronized (stream) {
                dropped = List.copyOf(stream.subscribers);
            }
            dropped.forEach(s -> unsubscribe(s.sessionId, s.subscriptionId));
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
        Closeable current;
        synchronized (stream) {
            stream.closed = true;
            current = stream.current;
            stream.current = null;
            stream.notifyAll(); // wakes a worker waiting for the stream end or in backoff; never interrupted
            if (stream.flush != null) {
                stream.flush.cancel(false);
            }
            stream.pending.clear();
        }
        closeQuietly(current);
    }

    /** Worker: connect, wait for the stream to end, report, back off, repeat until closed. */
    private void run(HostStream stream) {
        Duration delay = properties.reconnectInitial();
        try {
            while (!isClosed(stream)) {
                CompletableFuture<Throwable> ended = new CompletableFuture<>();
                String failure;
                try {
                    var host = hosts.findById(stream.hostId).orElse(null);
                    if (host == null) {
                        statusDisconnected(stream, DockerErrorCode.NOT_FOUND.name());
                        drop(stream);
                        return; // host is gone, retrying is pointless
                    }
                    if (isClosed(stream)) {
                        return; // released during the lookup: the lookup finished, no stream is opened
                    }
                    Closeable handle = events.stream(stream.hostId, endpoints.endpointOf(host),
                            event -> onEvent(stream, event), ended::complete);
                    synchronized (stream) {
                        if (stream.closed) {
                            closeQuietly(handle);
                            return;
                        }
                        stream.current = handle;
                    }
                    status(stream, DockerEventMessage.Status.CONNECTED, null);
                    delay = properties.reconnectInitial();
                    ended.whenComplete((end, error) -> {
                        synchronized (stream) {
                            stream.notifyAll();
                        }
                    });
                    synchronized (stream) {
                        while (!stream.closed && !ended.isDone()) {
                            stream.wait();
                        }
                    }
                    if (isClosed(stream)) {
                        return;
                    }
                    failure = codeOf(ended.getNow(null));
                } catch (DockerConnectionException e) {
                    failure = e.getCode().name();
                } catch (RuntimeException e) {
                    // e.g. undecryptable TLS material or missing encryption key: no detail to the client
                    log.warn("Docker event stream of host {} failed: {}", stream.hostId, e.getClass().getSimpleName());
                    failure = "UNAVAILABLE";
                }
                synchronized (stream) {
                    closeQuietly(stream.current);
                    stream.current = null;
                }
                if (isClosed(stream)) {
                    return;
                }
                statusDisconnected(stream, failure);
                awaitBackoff(stream, delay);
                if (isClosed(stream)) {
                    return;
                }
                delay = delay.multipliedBy(2).compareTo(properties.reconnectMax()) > 0
                        ? properties.reconnectMax() : delay.multipliedBy(2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sleeps for the backoff delay on the stream monitor; {@link #stop} wakes it early. */
    private void awaitBackoff(HostStream stream, Duration delay) throws InterruptedException {
        long deadline = System.nanoTime() + delay.toNanos();
        synchronized (stream) {
            long remaining;
            while (!stream.closed && (remaining = deadline - System.nanoTime()) > 0) {
                TimeUnit.NANOSECONDS.timedWait(stream, remaining);
            }
        }
    }

    /** A normal end of a stream we did not close means the daemon dropped us. */
    private static String codeOf(Throwable end) {
        if (end instanceof DockerConnectionException e) {
            return e.getCode().name();
        }
        return end == null ? "UNREACHABLE" : "UNAVAILABLE";
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
        }
    }

    private void statusDisconnected(HostStream stream, String code) {
        status(stream, DockerEventMessage.Status.DISCONNECTED, code);
    }

    /** Sends a STATUS frame unless it repeats the previous one. */
    private void status(HostStream stream, DockerEventMessage.Status status, String code) {
        synchronized (stream) {
            if (stream.closed && status != DockerEventMessage.Status.DISCONNECTED) {
                return;
            }
            DockerEventMessage last = stream.lastStatus;
            if (last != null && last.status() == status && java.util.Objects.equals(last.code(), code)
                    && status == DockerEventMessage.Status.DISCONNECTED) {
                return;
            }
            var message = new DockerEventMessage(stream.nextSeq++, DockerEventMessage.Type.STATUS, null, null,
                    status, code);
            stream.lastStatus = message;
            broadcast(stream, message);
        }
    }

    private void onEvent(HostStream stream, DockerEventDto event) {
        synchronized (stream) {
            if (stream.closed) {
                return;
            }
            EventKey key = new EventKey(event.type(), event.action(), event.id());
            if (!stream.pending.containsKey(key)) {
                if (stream.pending.size() >= properties.maxEventsPerFrame()) {
                    stream.omitted++;
                    return;
                }
                stream.pending.put(key, event);
            }
            if (stream.flush == null) {
                stream.flush = scheduler.schedule(() -> flush(stream), properties.debounce().toMillis(),
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    private void flush(HostStream stream) {
        synchronized (stream) {
            stream.flush = null;
            if (stream.closed || stream.pending.isEmpty()) {
                return;
            }
            var batch = List.copyOf(stream.pending.values());
            int omitted = stream.omitted;
            stream.pending.clear();
            stream.omitted = 0;
            broadcast(stream, new DockerEventMessage(stream.nextSeq++, DockerEventMessage.Type.EVENT, batch,
                    omitted > 0 ? omitted : null, null, null));
        }
    }

    /** Caller holds the stream lock. */
    private void broadcast(HostStream stream, DockerEventMessage message) {
        for (Subscription s : stream.subscribers) {
            send(stream, s, message);
        }
    }

    private void send(HostStream stream, Subscription sub, DockerEventMessage message) {
        sender.send(sub.sessionId, sub.subscriptionId, destination(stream.hostId), message);
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            // closing is best effort, the stream is gone either way
        }
    }
}
