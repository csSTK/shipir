package dev.bethge.shipir.streaming;

import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.ViewerErrorCodes;
import dev.bethge.shipir.docker.client.DockerEventService;
import dev.bethge.shipir.docker.client.ReconnectingEventStream;
import dev.bethge.shipir.docker.dto.DockerEventDto;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
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

    private record Subscription(String sessionId, String subscriptionId, String username, boolean admin) {
    }

    private record EventKey(DockerEventDto.Type type, String action, String id) {
    }

    private static final class HostStream {
        final UUID hostId;
        final List<Subscription> subscribers = new ArrayList<>();
        final Map<EventKey, DockerEventDto> pending = new LinkedHashMap<>();
        /** Events over the per-frame cap, kept by Compose project (null key = unlabelled) so each viewer gets its own count. */
        final Map<String, Integer> omitted = new HashMap<>();
        long nextSeq = 1;
        boolean closed;
        ReconnectingEventStream loop;
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
    private final StreamViewers viewers;
    private final StreamAccess access;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("docker-event-flush").factory());

    DockerEventStreamService(DockerEventService events, DockerHostRepository hosts, HostEndpointResolver endpoints,
                             StompFrameSender sender, DockerEventProperties properties,
                             StreamViewers viewers, StreamAccess access) {
        this.events = events;
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.sender = sender;
        this.properties = properties;
        this.viewers = viewers;
        this.access = access;
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

    /** Registers a subscription and starts the event worker for the first subscriber of the host. */
    void subscribe(UUID hostId, String username, String sessionId, String subscriptionId) {
        boolean admin = viewers.isAdmin(username); // initial value (subscribe thread); refreshAdmin keeps it current; frames are chosen per recipient
        HostStream stream;
        boolean first;
        while (true) {
            synchronized (streams) {
                stream = streams.get(hostId);
                first = stream == null;
                if (first) {
                    stream = new HostStream(hostId);
                    stream.loop = loopFor(stream);
                    streams.put(hostId, stream);
                }
            }
            synchronized (stream) {
                if (stream.closed) {
                    continue; // released concurrently, start over with a fresh stream
                }
                Subscription sub = new Subscription(sessionId, subscriptionId, username, admin);
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

    /**
     * The user's role changed: frames are chosen per recipient from this flag, so it must follow the role, not stay
     * what it was at SUBSCRIBE (a demoted ADMIN can still see the whole host and must not keep raw codes).
     */
    void refreshAdmin(String username, boolean admin) {
        List<HostStream> all;
        synchronized (streams) {
            all = new ArrayList<>(streams.values());
        }
        for (HostStream stream : all) {
            synchronized (stream) {
                stream.subscribers.replaceAll(s -> username.equals(s.username) && s.admin != admin
                        ? new Subscription(s.sessionId, s.subscriptionId, s.username, admin) : s);
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
            if (revoked.isEmpty()) {
                continue;
            }
            boolean allowed;
            try {
                allowed = hostStillAllowed.test(stream.hostId);
            } catch (RuntimeException e) {
                // fail closed for this host only; the remaining hosts are still checked
                log.warn("Event access revalidation failed for host {}, dropping the subscription: {}",
                        stream.hostId, e.getClass().getSimpleName());
                allowed = false;
            }
            if (allowed) {
                continue;
            }
            revoked.forEach(this::unsubscribeGuarded);
        }
    }

    /** One failing unsubscribe must not leave the remaining items of a revalidation or drop untouched. */
    private void unsubscribeGuarded(Subscription s) {
        try {
            unsubscribe(s.sessionId, s.subscriptionId);
        } catch (RuntimeException e) {
            log.warn("Dropping an event subscription failed: {}", e.getClass().getSimpleName());
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
            dropped.forEach(this::unsubscribeGuarded);
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
            stream.loop.stop(); // wakes a worker waiting for the stream end or in backoff and closes the stream
            if (stream.flush != null) {
                stream.flush.cancel(false);
            }
            stream.pending.clear();
        }
    }

    /** Worker: drives the shared reconnect loop; the listener turns its steps into STATUS frames. */
    private void run(HostStream stream) {
        stream.loop.run();
    }

    private ReconnectingEventStream loopFor(HostStream stream) {
        return new ReconnectingEventStream(stream.hostId, events, properties.reconnectInitial(),
                properties.reconnectMax(), new ReconnectingEventStream.Listener() {
            @Override
            public Optional<DockerEndpoint> endpoint() {
                return hosts.findById(stream.hostId).map(endpoints::endpointOf);
            }

            @Override
            public void onEvent(DockerEventDto event) {
                DockerEventStreamService.this.onEvent(stream, event);
            }

            @Override
            public void onConnected() {
                status(stream, DockerEventMessage.Status.CONNECTED, null);
            }

            @Override
            public void onDisconnected(String code) {
                statusDisconnected(stream, code);
            }

            @Override
            public void onHostGone() {
                statusDisconnected(stream, DockerErrorCode.NOT_FOUND.name());
                drop(stream);
            }
        });
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
                    stream.omitted.merge(event.composeProject(), 1, Integer::sum);
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

    /**
     * Debounce timer. The batch is taken under the stream lock, the system-project lookup (DB) runs outside it so it
     * cannot block subscribe/unsubscribe, then the frames are numbered and sent under the lock again (order kept).
     * Runs on the single flush thread, so flushes never overlap; events arriving meanwhile schedule the next one.
     */
    private void flush(HostStream stream) {
        List<DockerEventDto> batch;
        Map<String, Integer> omitted;
        boolean needsLookup;
        synchronized (stream) {
            stream.flush = null;
            if (stream.closed || stream.pending.isEmpty()) {
                return;
            }
            batch = List.copyOf(stream.pending.values());
            omitted = new HashMap<>(stream.omitted);
            stream.pending.clear();
            stream.omitted.clear();
            needsLookup = stream.subscribers.stream().anyMatch(s -> !s.admin);
        }
        Set<String> system = needsLookup ? systemProjects(stream) : null; // outside the lock
        synchronized (stream) {
            if (stream.closed) {
                return;
            }
            long seq = stream.nextSeq++;
            int omittedTotal = omitted.values().stream().mapToInt(Integer::intValue).sum();
            for (Subscription s : stream.subscribers) {
                // a non-ADMIN who subscribed during the lookup sees no project-labelled event (system == null = unknown)
                DockerEventMessage frame = s.admin
                        ? new DockerEventMessage(seq, DockerEventMessage.Type.EVENT, batch,
                                omittedTotal > 0 ? omittedTotal : null, null, null)
                        : forViewer(seq, batch, omitted, needsLookup ? system : null);
                send(stream, s, frame);
            }
        }
    }

    /**
     * Compose projects whose events only ADMINs see (the managed proxy); null = unknown (lookup failed), then every
     * project-labelled event is withheld from non-ADMINs.
     */
    private Set<String> systemProjects(HostStream stream) {
        try {
            return access.systemManagedProjects(stream.hostId);
        } catch (RuntimeException e) {
            log.warn("System-managed project lookup for host {} failed: {}", stream.hostId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The frame as a non-ADMIN may see it; null when nothing is left to show (no frame is sent then). The omitted
     * count leaves out dropped events of system projects (unknown lookup: all project-labelled ones), so hidden
     * proxy activity does not show up as a number either.
     */
    private static DockerEventMessage forViewer(long seq, List<DockerEventDto> batch, Map<String, Integer> omitted,
                                                Set<String> system) {
        List<DockerEventDto> visible = batch.stream()
                .filter(e -> e.composeProject() == null || (system != null && !system.contains(e.composeProject())))
                .toList();
        int omittedVisible = omitted.entrySet().stream()
                .filter(e -> e.getKey() == null || (system != null && !system.contains(e.getKey())))
                .mapToInt(Map.Entry::getValue).sum();
        if (visible.isEmpty() && omittedVisible == 0) {
            return null;
        }
        return new DockerEventMessage(seq, DockerEventMessage.Type.EVENT, visible,
                omittedVisible > 0 ? omittedVisible : null, null, null);
    }

    /** Caller holds the stream lock. */
    private void broadcast(HostStream stream, DockerEventMessage message) {
        for (Subscription s : stream.subscribers) {
            send(stream, s, message);
        }
    }

    private void send(HostStream stream, Subscription sub, DockerEventMessage message) {
        if (message == null) {
            return;
        }
        if (message.type() == DockerEventMessage.Type.STATUS && message.code() != null) {
            // the stream (and its replayed latest STATUS) holds the raw code; each recipient gets its own view of it
            message = new DockerEventMessage(message.seq(), message.type(), null, null, message.status(),
                    ViewerErrorCodes.forStreamFrame(message.code(), sub.admin));
        }
        sender.send(sub.sessionId, sub.subscriptionId, destination(stream.hostId), message);
    }
}
