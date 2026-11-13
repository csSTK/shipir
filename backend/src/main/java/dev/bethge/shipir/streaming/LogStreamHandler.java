package dev.bethge.shipir.streaming;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.ViewerErrorCodes;
import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.docker.dto.LogLine;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Live container logs on {@code /topic/hosts/{hostId}/containers/{containerId}/logs}.
 *
 * <p>Subscriptions are reference counted per (host, container, tail, timestamps): the first SUBSCRIBE starts one Docker follow
 * (on a virtual thread, so the STOMP thread never waits for Docker), later subscribers share it and get a
 * bounded replay of recent lines, the last UNSUBSCRIBE or disconnect closes it. Frames go directly to each
 * subscription under the stream lock, so replay and live delivery stay ordered. When the container stops, a
 * STATUS ENDED frame is sent. Lines are capped in length and in rate.
 */
@Service
@EnableConfigurationProperties(LogStreamProperties.class)
public class LogStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(LogStreamHandler.class);

    static final String TOPIC_PREFIX = "/topic/hosts/";
    private static final Pattern TOPIC = Pattern.compile(
            "^" + Pattern.quote(TOPIC_PREFIX) + "([0-9a-fA-F-]{36})/containers/([A-Za-z0-9][A-Za-z0-9_.-]{0,127})/logs$");

    /** A parsed log topic. */
    record Target(UUID hostId, String containerId) {
    }

    /** Parsed SUBSCRIBE options; {@code tail} is the line count, negative = all. */
    record Options(int tail, boolean timestamps) {
    }

    /** Streams are shared per container and options. */
    private record Key(Target target, Options options) {
    }

    private record Subscription(String sessionId, String subscriptionId, String username, boolean admin) {
    }

    private static final class Stream {
        final Key key;
        final Target target;
        final Deque<LogStreamMessage> buffer = new ArrayDeque<>();
        final List<Subscription> subscribers = new ArrayList<>();
        long nextSeq = 1;
        boolean ended;
        boolean closed;
        /** Lines the Docker follow replays at start (negative = all, used with followSince). */
        int followTail;
        /** Lower bound for the Docker follow (carried-over viewers); null = no bound. */
        Instant followSince;
        /** When the stream ended (set by finish). */
        Instant endedAt;
        /** Docker (daemon-clock) timestamp of the last delivered line; only known with timestamps=true. */
        Instant lastDaemonTimestamp;
        Closeable follow;
        final LogStreamSupport.RateWindow rate = new LogStreamSupport.RateWindow();

        Stream(Key key) {
            this.key = key;
            this.target = key.target();
            this.followTail = key.options().tail();
        }
    }

    private final Map<Key, Stream> streams = new HashMap<>();
    private final ContainerLogService logs;
    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final StompFrameSender sender;
    private final LogStreamProperties properties;
    private final Clock clock;
    private final StreamViewers viewers;

    LogStreamHandler(ContainerLogService logs, DockerHostRepository hosts, HostEndpointResolver endpoints,
                     StompFrameSender sender, LogStreamProperties properties, Clock clock, StreamViewers viewers) {
        this.viewers = viewers;
        this.logs = logs;
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    /** Parses a destination; null if it is not a log topic. */
    static Target parse(String destination) {
        var m = TOPIC.matcher(destination);
        if (!m.matches()) {
            return null;
        }
        try {
            return new Target(UUID.fromString(m.group(1)), m.group(2));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Reads the {@code tail} (100, 500, 1000, all; default from properties) and {@code timestamps} (true, false;
     * default false) SUBSCRIBE headers. Throws {@link IllegalArgumentException} for any other value.
     */
    Options parseOptions(String tailHeader, String timestampsHeader) {
        int tail = LogStreamSupport.parseTail(tailHeader, properties.tail());
        boolean timestamps = LogStreamSupport.parseTimestamps(timestampsHeader);
        return new Options(tail, timestamps);
    }

    /** Registers a subscription, replays the buffer and starts the Docker follow for the first subscriber. */
    void subscribe(Target target, Options options, String username, String sessionId, String subscriptionId) {
        Key key = new Key(target, options);
        boolean admin = viewers.isAdmin(username); // initial value (subscribe thread); refreshAdmin keeps it current
        Stream stream;
        boolean first;
        while (true) {
            synchronized (streams) {
                stream = streams.get(key);
                Stream replaced = stream != null ? replaceIfEnded(stream) : null;
                if (replaced != null) {
                    stream = null;
                }
                first = stream == null;
                if (first) {
                    stream = new Stream(key);
                    if (replaced != null) {
                        // the old viewers stay with the container: they keep being revalidated and seq goes on
                        synchronized (replaced) {
                            stream.subscribers.addAll(replaced.subscribers);
                            replaced.subscribers.clear();
                            stream.nextSeq = replaced.nextSeq;
                            // Carried-over viewers saw the old lines, so a tail replay would show them again with
                            // new seqs. Follow with since instead (all lines of that window, so output between the
                            // end and now, e.g. a restart, is not lost) and carry the old LINE buffer over, so a
                            // joiner still gets recent lines. Docker evaluates since on the DAEMON clock, so it is
                            // the last delivered line's Docker timestamp when known (timestamps on), else the
                            // Shipir end time minus the configured safety margin (LogStreamSupport.followSince).
                            // Docker's since has second precision: some lines may arrive twice (accepted, not
                            // deduped). If the
                            // old stream never delivered a line (it failed to start) the requested tail stays.
                            List<LogStreamMessage> oldLines = replaced.buffer.stream()
                                    .filter(m -> m.type() == LogStreamMessage.Type.LINE).toList();
                            if (!oldLines.isEmpty() && replaced.endedAt != null) {
                                stream.followTail = -1;
                                stream.lastDaemonTimestamp = replaced.lastDaemonTimestamp; // flapping: stay on the daemon clock
                                stream.followSince = LogStreamSupport.followSince(replaced.lastDaemonTimestamp,
                                        replaced.endedAt, properties.sinceSafetyMargin());
                                oldLines.forEach(stream.buffer::addLast);
                            }
                        }
                    }
                    streams.put(key, stream);
                }
            }
            synchronized (stream) {
                if (stream.closed) {
                    continue; // released concurrently, start over with a fresh stream
                }
                Subscription sub = new Subscription(sessionId, subscriptionId, username, admin);
                stream.subscribers.add(sub);
                for (LogStreamMessage m : stream.buffer) {
                    send(stream, sub, m);
                }
                break;
            }
        }
        if (first) {
            Stream started = stream;
            Thread.ofVirtual().name("log-follow-" + target.containerId()).start(() -> startFollow(started));
        }
    }

    /**
     * An ended stream (container stopped or follow failed) will never deliver again, but stays in the map while a
     * viewer remains. A new subscriber must get a fresh follow, so the ended stream is closed and removed. Returns the
     * closed stream (the caller moves its viewers to the fresh one) or null if it was not ended. Caller holds the
     * {@code streams} lock.
     */
    private Stream replaceIfEnded(Stream stream) {
        Closeable follow;
        synchronized (stream) {
            if (!stream.ended || stream.closed) {
                return null;
            }
            stream.closed = true;
            follow = stream.follow;
            stream.follow = null;
        }
        streams.remove(stream.key, stream);
        LogStreamSupport.closeQuietly(follow);
        return stream;
    }

    /**
     * The user's role changed: frames are chosen per recipient from this flag, so it must follow the role, not stay
     * what it was at SUBSCRIBE (a demoted ADMIN can still see the whole host and must not keep raw codes).
     */
    void refreshAdmin(String username, boolean admin) {
        List<Stream> all;
        synchronized (streams) {
            all = new ArrayList<>(streams.values());
        }
        for (Stream stream : all) {
            synchronized (stream) {
                stream.subscribers.replaceAll(s -> username.equals(s.username) && s.admin != admin
                        ? new Subscription(s.sessionId, s.subscriptionId, s.username, admin) : s);
            }
        }
    }

    void unsubscribe(String sessionId, String subscriptionId) {
        List<Stream> empty = new ArrayList<>();
        // under the streams lock, so a stream replaced meanwhile cannot take the subscription with it unseen
        synchronized (streams) {
            for (Stream stream : streams.values()) {
                synchronized (stream) {
                    stream.subscribers.removeIf(s -> s.sessionId.equals(sessionId)
                            && (subscriptionId == null || s.subscriptionId.equals(subscriptionId)));
                    if (stream.subscribers.isEmpty()) {
                        empty.add(stream);
                    }
                }
            }
        }
        empty.forEach(this::release);
    }

    /**
     * Drops the user's subscriptions whose container they may no longer follow (role, grant or account changed after
     * SUBSCRIBE). Nothing is sent to them any more; the STOMP connection itself stays.
     */
    void revalidate(String username, Predicate<Target> stillAllowed) {
        List<Stream> all;
        synchronized (streams) {
            all = new ArrayList<>(streams.values());
        }
        for (Stream stream : all) {
            List<Subscription> own;
            synchronized (stream) {
                own = stream.subscribers.stream().filter(s -> username.equals(s.username)).toList();
            }
            // the predicate may look the container up in Docker: only ask for streams this user is on
            if (own.isEmpty()) {
                continue;
            }
            boolean allowed;
            try {
                allowed = stillAllowed.test(stream.target);
            } catch (RuntimeException e) {
                // fail closed for this stream only; the remaining streams are still checked
                log.warn("Log access revalidation failed for {} on host {}, dropping the subscription: {}",
                        stream.target.containerId(), stream.target.hostId(), e.toString());
                allowed = false;
            }
            if (!allowed) {
                own.forEach(s -> unsubscribe(s.sessionId, s.subscriptionId));
            }
        }
    }

    /**
     * Like {@link #revalidate} for every subscriber, whoever it is: used when something changed that no user event
     * announces (a deleted deployment takes its grants with it). Fails closed per subscription.
     */
    void revalidateAll(java.util.function.BiPredicate<String, Target> stillAllowed) {
        List<Stream> all;
        synchronized (streams) {
            all = new ArrayList<>(streams.values());
        }
        for (Stream stream : all) {
            List<Subscription> subscribers;
            synchronized (stream) {
                subscribers = List.copyOf(stream.subscribers);
            }
            for (String user : subscribers.stream().map(s -> s.username).distinct().toList()) {
                boolean allowed;
                try {
                    allowed = stillAllowed.test(user, stream.target);
                } catch (RuntimeException e) {
                    log.warn("Log access revalidation failed on host {}, dropping the subscription: {}",
                            stream.target.hostId(), e.getClass().getSimpleName());
                    allowed = false;
                }
                if (!allowed) {
                    subscribers.stream().filter(s -> user.equals(s.username))
                            .forEach(s -> unsubscribe(s.sessionId, s.subscriptionId));
                }
            }
        }
    }

    /** Drops every subscription on a deleted host, whoever holds it. */
    void dropHost(UUID hostId) {
        List<Stream> all;
        synchronized (streams) {
            all = new ArrayList<>(streams.values());
        }
        for (Stream stream : all) {
            if (!stream.target.hostId().equals(hostId)) {
                continue;
            }
            List<Subscription> dropped;
            synchronized (stream) {
                dropped = List.copyOf(stream.subscribers);
            }
            dropped.forEach(s -> unsubscribe(s.sessionId, s.subscriptionId));
        }
    }

    /** Subscribers of a container across all option variants. */
    int subscriberCount(Target target) {
        List<Stream> matching;
        synchronized (streams) {
            matching = streams.values().stream().filter(s -> s.target.equals(target)).toList();
        }
        int count = 0;
        for (Stream stream : matching) {
            synchronized (stream) {
                count += stream.subscribers.size();
            }
        }
        return count;
    }

    int activeStreams() {
        synchronized (streams) {
            return streams.size();
        }
    }

    private void release(Stream stream) {
        Closeable follow;
        synchronized (streams) {
            synchronized (stream) {
                if (!stream.subscribers.isEmpty() || stream.closed) {
                    return;
                }
                stream.closed = true;
                follow = stream.follow;
                stream.follow = null;
            }
            streams.remove(stream.key, stream);
        }
        LogStreamSupport.closeQuietly(follow);
    }

    private void startFollow(Stream stream) {
        Target target = stream.target;
        try {
            var host = hosts.findById(target.hostId())
                    .orElseThrow(() -> new DockerConnectionException(
                            dev.bethge.shipir.docker.DockerErrorCode.NOT_FOUND, "Host not found", null));
            var endpoint = endpoints.endpointOf(host);
            Closeable follow = stream.followSince == null
                    ? logs.follow(target.hostId(), endpoint, target.containerId(), stream.followTail,
                            stream.key.options().timestamps(), line -> onLine(stream, line), failure -> onEnd(stream, failure))
                    : logs.follow(target.hostId(), endpoint, target.containerId(), stream.followTail,
                            stream.followSince, stream.key.options().timestamps(),
                            line -> onLine(stream, line), failure -> onEnd(stream, failure));
            boolean stop;
            synchronized (stream) {
                stop = stream.closed;
                if (!stop) {
                    stream.follow = follow;
                }
            }
            if (stop) {
                LogStreamSupport.closeQuietly(follow);
            }
        } catch (DockerConnectionException e) {
            fail(stream, e.getCode().name());
        } catch (RuntimeException e) {
            // e.g. undecryptable TLS material or missing encryption key: no detail to the client
            log.warn("Starting log follow for container {} failed: {}", target.containerId(), e.getClass().getSimpleName());
            fail(stream, "UNAVAILABLE");
        }
    }

    private void onLine(Stream stream, LogLine line) {
        synchronized (stream) {
            if (stream.ended || stream.closed) {
                return;
            }
            int skipped = stream.rate.roll(clock.millis() / 1000);
            if (skipped > 0) {
                publish(stream, LogStreamMessage.Type.LINE, "STDERR", LogStreamSupport.skippedMarker(skipped), null, null);
            }
            if (stream.rate.isFull(properties.maxLinesPerSecond())) {
                stream.rate.drop();
                return;
            }
            stream.rate.take();
            if (line.timestamp() != null) {
                stream.lastDaemonTimestamp = line.timestamp();
            }
            String text = LogStreamSupport.capLength(line.text(), properties.maxLineLength());
            String timestamp = LogStreamSupport.timestampOf(line, stream.key.options().timestamps());
            publish(stream, LogStreamMessage.Type.LINE, line.stream().name(), text, null, timestamp);
        }
    }

    private void onEnd(Stream stream, Throwable failure) {
        if (failure instanceof DockerConnectionException e) {
            fail(stream, e.getCode().name());
        } else if (failure != null) {
            fail(stream, "UNAVAILABLE");
        } else {
            synchronized (stream) {
                finish(stream, LogStreamMessage.Status.ENDED, null);
            }
        }
    }

    private void fail(Stream stream, String code) {
        synchronized (stream) {
            finish(stream, LogStreamMessage.Status.ERROR, code);
        }
    }

    private void finish(Stream stream, LogStreamMessage.Status status, String code) {
        if (stream.ended || stream.closed) {
            return;
        }
        stream.ended = true;
        stream.endedAt = clock.instant();
        publish(stream, LogStreamMessage.Type.STATUS, null, code, status, null);
    }

    /** Caller holds the stream lock. */
    private void publish(Stream stream, LogStreamMessage.Type type, String streamName, String line,
                         LogStreamMessage.Status status, String timestamp) {
        var message = new LogStreamMessage(stream.nextSeq++, type, streamName, line, status, timestamp);
        stream.buffer.addLast(message);
        int capacity = Math.max(properties.bufferLines(), stream.key.options().tail());
        while (stream.buffer.size() > capacity && stream.buffer.peekFirst().type() == LogStreamMessage.Type.LINE) {
            stream.buffer.removeFirst();
        }
        for (Subscription s : stream.subscribers) {
            send(stream, s, message);
        }
    }

    private void send(Stream stream, Subscription sub, LogStreamMessage message) {
        if (message.type() == LogStreamMessage.Type.STATUS && message.status() == LogStreamMessage.Status.ERROR) {
            // the buffered frame keeps the raw code; each recipient gets the one it may see (also on replay)
            message = new LogStreamMessage(message.seq(), message.type(), message.stream(),
                    ViewerErrorCodes.forStreamFrame(message.line(), sub.admin), message.status(), message.timestamp());
        }
        sender.send(sub.sessionId, sub.subscriptionId, destination(stream.target), message);
    }

    static String destination(Target t) {
        return TOPIC_PREFIX + t.hostId() + "/containers/" + t.containerId() + "/logs";
    }
}
