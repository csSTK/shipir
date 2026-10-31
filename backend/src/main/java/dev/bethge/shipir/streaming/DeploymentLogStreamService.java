package dev.bethge.shipir.streaming;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerLogService;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.LogLine;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Combined live logs of a Compose project on {@code /topic/hosts/{hostId}/deployments/{project}/logs}: the output of
 * all containers of the project in arrival order, every frame naming its service and container.
 *
 * <p>Like {@link LogStreamHandler}, subscriptions are reference counted per (host, project, tail, timestamps): the
 * first SUBSCRIBE starts a worker (virtual thread) that follows every current container of the project, later
 * subscribers share it and get a bounded replay, the last UNSUBSCRIBE or disconnect closes all followers and stops the
 * worker. Containers that appear later (scale-up, redeploy, restart) are found by <b>re-listing the project's containers
 * every {@code refresh-interval}</b> while somebody is subscribed; a new container is followed with the subscribed tail,
 * a restarted one (same id, running again) with tail 0, so lines are not repeated. No Docker event stream is used, which
 * keeps the worker independent from {@link DockerEventStreamService}. Lines between a container start and the next
 * refresh are delivered through the tail of the new follow, so none are lost for new containers; for a restarted
 * container, lines written before the refresh noticed it may be missed.
 *
 * <p>Bounds: at most {@code max-containers} followed per project (the rest is reported once as project-level
 * CONTAINER_LIMIT), line length, lines per second per container and for the whole project (excess dropped and
 * summarized in one marker line), and the replay buffer. Known limit: a container whose follow could not be started
 * (e.g. the daemon refused the log call) is reported once and retried by the worker at most {@link #MAX_START_RETRIES}
 * times with backoff; after that it stays failed until the container is seen stopped and running again (restart), which starts over, or
 * until the last subscriber left and a new SUBSCRIBE starts over.
 * Lines of different containers are not re-sorted by time.
 */
@Service
@EnableConfigurationProperties(DeploymentLogProperties.class)
public class DeploymentLogStreamService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentLogStreamService.class);

    /**
     * A follow that could not be started is retried by the refresh worker itself (no new SUBSCRIBE needed), at most
     * this many times, waiting 1, 2, 4 refresh rounds in between (backoff in rounds, not wall-clock, so it needs no
     * timer and scales with {@code refresh-interval}). Bounded so a container whose log call is refused for good does
     * not hit the daemon for as long as somebody watches; the error is published only for the first failure.
     */
    static final int MAX_START_RETRIES = 3;

    static final String TOPIC_PREFIX = "/topic/hosts/";
    private static final Pattern TOPIC = Pattern.compile(
            "^" + Pattern.quote(TOPIC_PREFIX) + "([0-9a-fA-F-]{36})/deployments/([a-z0-9][a-z0-9_-]{0,62})/logs$");

    /** A parsed log topic. */
    record Target(UUID hostId, String project) {
    }

    /** Parsed SUBSCRIBE options; {@code tail} is the line count per container, negative = all. */
    record Options(int tail, boolean timestamps) {
    }

    private record Key(Target target, Options options) {
    }

    private record Subscription(String sessionId, String subscriptionId) {
    }

    /** One followed container. */
    private static final class Follower {
        final String containerId;
        final String service;
        final String name;
        Closeable follow;
        /** Incremented per follow so callbacks of an older follow are ignored. */
        int generation;
        boolean ended;
        boolean failed;
        /** Consecutive failed starts; reset by a successful start. */
        int failures;
        /** Refresh rounds to wait before the next retry of a failed start. */
        int ticksUntilRetry;
        /** Set while retries are exhausted and the container was seen not running; a later running state resets them. */
        boolean sawStopped;
        final LogStreamSupport.RateWindow rate = new LogStreamSupport.RateWindow();

        Follower(ContainerDto c) {
            this.containerId = c.id();
            this.service = c.composeService();
            this.name = c.name();
        }
    }

    private static final class Stream {
        final Key key;
        final Deque<DeploymentLogMessage> buffer = new ArrayDeque<>();
        final List<Subscription> subscribers = new ArrayList<>();
        final Map<String, Follower> followers = new LinkedHashMap<>();
        final Set<String> reportedCodes = new HashSet<>();
        long nextSeq = 1;
        boolean closed;
        boolean limitReported;
        final LogStreamSupport.RateWindow rate = new LogStreamSupport.RateWindow();

        Stream(Key key) {
            this.key = key;
        }
    }

    private final Map<Key, Stream> streams = new HashMap<>();
    private final ContainerService containers;
    private final ContainerLogService logs;
    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final StompFrameSender sender;
    private final DeploymentLogProperties properties;
    private final Clock clock;

    DeploymentLogStreamService(ContainerService containers, ContainerLogService logs, DockerHostRepository hosts,
                               HostEndpointResolver endpoints, StompFrameSender sender,
                               DeploymentLogProperties properties, Clock clock) {
        this.containers = containers;
        this.logs = logs;
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    /** Parses a destination; null if it is not a deployment log topic (the project must be a valid Compose project name). */
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

    /** Registers a subscription, replays the buffer and starts the worker for the first subscriber. */
    void subscribe(Target target, Options options, String sessionId, String subscriptionId) {
        Key key = new Key(target, options);
        Stream stream;
        boolean first;
        while (true) {
            synchronized (streams) {
                stream = streams.get(key);
                first = stream == null;
                if (first) {
                    stream = new Stream(key);
                    streams.put(key, stream);
                }
            }
            synchronized (stream) {
                if (stream.closed) {
                    continue; // released concurrently, start over with a fresh stream
                }
                Subscription sub = new Subscription(sessionId, subscriptionId);
                stream.subscribers.add(sub);
                for (DeploymentLogMessage m : stream.buffer) {
                    send(stream, sub, m);
                }
                break;
            }
        }
        if (first) {
            Stream started = stream;
            Thread.ofVirtual().name("deployment-logs-" + target.project()).start(() -> run(started));
        }
    }

    void unsubscribe(String sessionId, String subscriptionId) {
        List<Stream> all;
        synchronized (streams) {
            all = new ArrayList<>(streams.values());
        }
        for (Stream stream : all) {
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

    /** Subscribers of a project across all option variants. */
    int subscriberCount(Target target) {
        List<Stream> matching;
        synchronized (streams) {
            matching = streams.values().stream().filter(s -> s.key.target().equals(target)).toList();
        }
        int count = 0;
        for (Stream stream : matching) {
            synchronized (stream) {
                count += stream.subscribers.size();
            }
        }
        return count;
    }

    /** The worker ends for good (e.g. host gone): drop the stream from the map and close its followers. */
    private void terminate(Stream stream) {
        List<Closeable> toClose = new ArrayList<>();
        synchronized (streams) {
            synchronized (stream) {
                if (!stream.closed) {
                    stream.closed = true;
                    for (Follower f : stream.followers.values()) {
                        if (f.follow != null) {
                            toClose.add(f.follow);
                            f.follow = null;
                        }
                    }
                }
            }
            streams.remove(stream.key, stream);
        }
        toClose.forEach(LogStreamSupport::closeQuietly);
    }

    private void release(Stream stream) {
        List<Closeable> toClose = new ArrayList<>();
        synchronized (streams) {
            synchronized (stream) {
                if (!stream.subscribers.isEmpty() || stream.closed) {
                    return;
                }
                stream.closed = true;
                for (Follower f : stream.followers.values()) {
                    if (f.follow != null) {
                        toClose.add(f.follow);
                        f.follow = null;
                    }
                }
                stream.notifyAll(); // wakes the worker so it ends
            }
            streams.remove(stream.key, stream);
        }
        toClose.forEach(LogStreamSupport::closeQuietly);
    }

    // ---- worker ----

    private void run(Stream stream) {
        while (true) {
            boolean stop = refresh(stream);
            if (stop) {
                terminate(stream); // a later SUBSCRIBE must get a fresh stream and worker, not this dead one
                return;
            }
            synchronized (stream) {
                if (stream.closed) {
                    return;
                }
                try {
                    stream.wait(Math.max(1, properties.refreshInterval().toMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (stream.closed) {
                    return;
                }
            }
        }
    }

    /** One re-listing of the project's containers; returns true when the worker should stop for good. */
    private boolean refresh(Stream stream) {
        Target target = stream.key.target();
        List<ContainerDto> current;
        DockerEndpoint endpoint;
        try {
            DockerHost host = hosts.findById(target.hostId()).orElseThrow(() ->
                    new DockerConnectionException(DockerErrorCode.NOT_FOUND, "Host not found", null));
            endpoint = endpoints.endpointOf(host);
            current = containers.listContainers(target.hostId(), endpoint).stream()
                    .filter(c -> target.project().equals(c.composeProject()) && c.composeService() != null)
                    .sorted(Comparator.comparing(ContainerDto::name, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        } catch (DockerConnectionException e) {
            projectError(stream, e.getCode().name());
            return e.getCode() == DockerErrorCode.NOT_FOUND;
        } catch (RuntimeException e) {
            // e.g. undecryptable TLS material or missing encryption key: no detail to the client
            log.warn("Listing containers of project {} failed: {}", target.project(), e.getClass().getSimpleName());
            projectError(stream, "UNAVAILABLE");
            return false;
        }

        List<Follower> toStart = new ArrayList<>();
        List<Integer> tails = new ArrayList<>();
        List<Integer> generations = new ArrayList<>();
        synchronized (stream) {
            if (stream.closed) {
                return true;
            }
            Set<String> listed = new HashSet<>();
            for (ContainerDto c : current) {
                listed.add(c.id());
                Follower f = stream.followers.get(c.id());
                if (f == null) {
                    if (stream.followers.size() >= properties.maxContainers()) {
                        if (!stream.limitReported) {
                            stream.limitReported = true;
                            publish(stream, DeploymentLogMessage.Type.STATUS, null, null, "CONTAINER_LIMIT",
                                    DeploymentLogMessage.Status.ERROR, null);
                        }
                        continue;
                    }
                    f = new Follower(c);
                    stream.followers.put(c.id(), f);
                    toStart.add(f);
                    tails.add(stream.key.options().tail());
                    generations.add(++f.generation);
                } else if (f.ended && f.failed) {
                    boolean running = "running".equals(c.state());
                    if (f.failures > MAX_START_RETRIES) {
                        // retries exhausted: only a restart (seen not running, then running again) starts over
                        if (!running) {
                            f.sawStopped = true;
                        } else if (f.sawStopped) {
                            f.sawStopped = false;
                            f.failures = 0;
                            f.ticksUntilRetry = 0;
                        }
                    }
                    if (f.failures <= MAX_START_RETRIES && --f.ticksUntilRetry <= 0) {
                        f.ended = false;
                        toStart.add(f);
                        tails.add(stream.key.options().tail()); // nothing was delivered yet
                        generations.add(++f.generation);
                    }
                } else if (f.ended && "running".equals(c.state())) {
                    f.ended = false;
                    toStart.add(f);
                    tails.add(0); // restarted: the old lines were delivered by the previous follow
                    generations.add(++f.generation);
                }
            }
            // containers that are gone free their slot once their stream has ended
            stream.followers.values().removeIf(f -> f.ended && !listed.contains(f.containerId));
        }

        for (int i = 0; i < toStart.size(); i++) {
            startFollow(stream, endpoint, toStart.get(i), tails.get(i), generations.get(i));
        }
        return false;
    }

    private void startFollow(Stream stream, DockerEndpoint endpoint, Follower f, int tail, int generation) {
        try {
            Closeable follow = logs.follow(stream.key.target().hostId(), endpoint, f.containerId, tail,
                    stream.key.options().timestamps(), line -> onLine(stream, f, generation, line),
                    failure -> onEnd(stream, f, generation, failure));
            boolean stop;
            synchronized (stream) {
                stop = stream.closed || f.generation != generation;
                if (!stop) {
                    f.follow = follow;
                    f.failed = false;
                    f.failures = 0;
                }
            }
            if (stop) {
                LogStreamSupport.closeQuietly(follow);
            }
        } catch (DockerConnectionException e) {
            containerFailed(stream, f, e.getCode().name());
        } catch (RuntimeException e) {
            log.warn("Starting log follow for container {} failed: {}", f.containerId, e.getClass().getSimpleName());
            containerFailed(stream, f, "UNAVAILABLE");
        }
    }

    private void containerFailed(Stream stream, Follower f, String code) {
        synchronized (stream) {
            if (stream.closed) {
                return;
            }
            f.ended = true;
            f.failed = true;
            f.failures++;
            f.ticksUntilRetry = 1 << Math.min(f.failures - 1, MAX_START_RETRIES);
            if (f.failures == 1) { // retries stay silent, the client already knows
                publish(stream, DeploymentLogMessage.Type.STATUS, f, null, code,
                        DeploymentLogMessage.Status.ERROR, null);
            }
        }
    }

    /** Project-level problem, reported once per distinct code; the worker keeps trying. */
    private void projectError(Stream stream, String code) {
        synchronized (stream) {
            if (stream.closed || !stream.reportedCodes.add(code)) {
                return;
            }
            publish(stream, DeploymentLogMessage.Type.STATUS, null, null, code,
                    DeploymentLogMessage.Status.ERROR, null);
        }
    }

    private void onLine(Stream stream, Follower f, int generation, LogLine line) {
        synchronized (stream) {
            if (stream.closed || f.ended || f.generation != generation) {
                return;
            }
            long second = clock.millis() / 1000;
            int skipped = stream.rate.roll(second);
            if (skipped > 0) {
                publish(stream, DeploymentLogMessage.Type.LINE, null, "STDERR", LogStreamSupport.skippedMarker(skipped),
                        null, null);
            }
            int skippedOfContainer = f.rate.roll(second);
            if (skippedOfContainer > 0) {
                publish(stream, DeploymentLogMessage.Type.LINE, f, "STDERR",
                        LogStreamSupport.skippedMarker(skippedOfContainer), null, null);
            }
            if (f.rate.isFull(properties.maxLinesPerSecondPerContainer())) {
                f.rate.drop();
                return;
            }
            if (stream.rate.isFull(properties.maxLinesPerSecond())) {
                stream.rate.drop();
                return;
            }
            f.rate.take();
            stream.rate.take();
            String text = LogStreamSupport.capLength(line.text(), properties.maxLineLength());
            String timestamp = LogStreamSupport.timestampOf(line, stream.key.options().timestamps());
            publish(stream, DeploymentLogMessage.Type.LINE, f, line.stream().name(), text, null, timestamp);
        }
    }

    private void onEnd(Stream stream, Follower f, int generation, Throwable failure) {
        synchronized (stream) {
            if (stream.closed || f.ended || f.generation != generation) {
                return;
            }
            f.ended = true;
            f.follow = null;
            if (failure == null) {
                publish(stream, DeploymentLogMessage.Type.STATUS, f, null, null,
                        DeploymentLogMessage.Status.ENDED, null);
            } else {
                String code = failure instanceof DockerConnectionException e ? e.getCode().name() : "UNAVAILABLE";
                publish(stream, DeploymentLogMessage.Type.STATUS, f, null, code,
                        DeploymentLogMessage.Status.ERROR, null);
            }
        }
    }

    /** Caller holds the stream lock. {@code f} null = about the whole project. */
    private void publish(Stream stream, DeploymentLogMessage.Type type, Follower f, String streamName, String line,
                         DeploymentLogMessage.Status status, String timestamp) {
        var message = new DeploymentLogMessage(stream.nextSeq++, type, f == null ? null : f.service,
                f == null ? null : f.name, f == null ? null : f.containerId, streamName, line, status, timestamp);
        stream.buffer.addLast(message);
        while (stream.buffer.size() > properties.bufferLines()) {
            stream.buffer.removeFirst();
        }
        for (Subscription s : stream.subscribers) {
            send(stream, s, message);
        }
    }

    private void send(Stream stream, Subscription sub, DeploymentLogMessage message) {
        sender.send(sub.sessionId, sub.subscriptionId, destination(stream.key.target()), message);
    }

    static String destination(Target t) {
        return TOPIC_PREFIX + t.hostId() + "/deployments/" + t.project() + "/logs";
    }
}
