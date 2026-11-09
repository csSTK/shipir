package dev.bethge.shipir.streaming;

import dev.bethge.shipir.common.stream.RunOutput;
import dev.bethge.shipir.common.stream.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.OrderedMessageChannelDecorator;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Fan-out of Compose output to STOMP subscribers of {@code /topic/deployments/{runId}}.
 *
 * <p>Usage by the deploy code: {@link #start} returns the line sink for
 * {@code ComposeExecutionService.execute(..., onLine)}, {@link #complete} publishes the final status.
 * A bounded in-memory buffer lets late subscribers catch up. Frames are delivered directly to each
 * subscription (not through the simple broker) so that replay and live delivery are ordered under one lock
 * and a line can neither be lost nor duplicated between replay and subscribe. Lines are forwarded exactly as
 * Compose printed them; nothing is added.
 */
@Service
@EnableConfigurationProperties(StreamProperties.class)
public class ComposeOutputStreamService implements RunOutput {

    private static final Logger log = LoggerFactory.getLogger(ComposeOutputStreamService.class);

    public static final String TOPIC_PREFIX = "/topic/deployments/";

    private record Subscription(String username, String sessionId, String subscriptionId) {
    }

    private static final class Stream {
        final Deque<StreamMessage> buffer = new ArrayDeque<>();
        final List<Subscription> subscribers = new ArrayList<>();
        long nextSeq = 1;
        RunStatus status;
        Instant finishedAt;
    }

    private final Map<String, Stream> streams = new HashMap<>();
    /** Which host/deployment a run belongs to (for the SUBSCRIBE permission check); trimmed together with the streams. */
    private final Map<String, RunTarget> runTargets = new java.util.concurrent.ConcurrentHashMap<>();

    /** Host and managed deployment of a run. */
    record RunTarget(java.util.UUID hostId, java.util.UUID deploymentId) {
    }

    /** Records which deployment (null for a host-level run such as an image pull) a run belongs to; call before {@link #start}. */
    @Override
    public void registerRun(String runId, java.util.UUID hostId, java.util.UUID deploymentId) {
        runTargets.put(runId, new RunTarget(hostId, deploymentId));
    }

    Optional<RunTarget> targetOf(String runId) {
        return Optional.ofNullable(runTargets.get(runId));
    }
    private final MessageChannel clientOutbound;
    private final JsonMapper mapper;
    private final StreamProperties properties;
    private final Clock clock;

    ComposeOutputStreamService(@Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutbound,
                               JsonMapper mapper, StreamProperties properties, Clock clock) {
        // the outbound channel is a thread pool: the decorator (enabled by setPreservePublishOrder in
        // WebSocketConfig) serializes sends per session so frames arrive in seq order
        this.clientOutbound = new OrderedMessageChannelDecorator(clientOutbound,
                org.apache.commons.logging.LogFactory.getLog(ComposeOutputStreamService.class));
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** Opens (or resets) the stream and returns the sink for Compose output lines. */
    @Override
    public Consumer<String> start(String runId) {
        synchronized (streams) {
            evictExpired();
            Stream old = streams.put(runId, new Stream());
            runTargets.keySet().retainAll(streams.keySet());
            if (old != null) {
                streams.get(runId).subscribers.addAll(old.subscribers);
            }
        }
        return line -> publish(runId, StreamMessage.Type.LINE, line, null);
    }

    /** Publishes the final status; later lines are ignored. */
    @Override
    public void complete(String runId, RunStatus status) {
        publish(runId, StreamMessage.Type.STATUS, null, status);
    }

    private void publish(String runId, StreamMessage.Type type, String line, RunStatus status) {
        Stream stream;
        synchronized (streams) {
            stream = streams.get(runId);
        }
        if (stream == null) {
            return;
        }
        synchronized (stream) {
            if (stream.status != null) {
                return;
            }
            StreamMessage message = new StreamMessage(stream.nextSeq++, type, line, status);
            stream.buffer.addLast(message);
            while (stream.buffer.size() > properties.bufferLines()) {
                stream.buffer.removeFirst();
            }
            if (type == StreamMessage.Type.STATUS) {
                stream.status = status;
                stream.finishedAt = clock.instant();
            }
            for (Subscription s : stream.subscribers) {
                send(runId, s, message);
            }
        }
    }

    /** Registers a subscription and replays the buffer to it (called on STOMP SUBSCRIBE, before the broker sees it). */
    void subscribe(String runId, String username, String sessionId, String subscriptionId) {
        Stream stream;
        synchronized (streams) {
            stream = streams.computeIfAbsent(runId, id -> new Stream());
        }
        synchronized (stream) {
            Subscription sub = new Subscription(username, sessionId, subscriptionId);
            stream.subscribers.add(sub);
            for (StreamMessage m : stream.buffer) {
                send(runId, sub, m);
            }
        }
    }

    int subscriberCount(String runId) {
        Stream stream;
        synchronized (streams) {
            stream = streams.get(runId);
        }
        if (stream == null) {
            return 0;
        }
        synchronized (stream) {
            return stream.subscribers.size();
        }
    }

    void unsubscribe(String sessionId, String subscriptionId) {
        removeIf(s -> s.sessionId.equals(sessionId) && (subscriptionId == null || s.subscriptionId.equals(subscriptionId)));
    }

    /**
     * Drops the user's subscriptions whose run they may no longer follow (role, grant or account changed after
     * SUBSCRIBE). Nothing is sent to them any more; the STOMP connection itself stays.
     */
    void revalidate(String username, Predicate<Optional<RunTarget>> runStillAllowed) {
        Map<String, Stream> all;
        synchronized (streams) {
            all = new HashMap<>(streams);
        }
        all.forEach((runId, stream) -> {
            boolean subscribed;
            synchronized (stream) {
                subscribed = stream.subscribers.stream().anyMatch(s -> username.equals(s.username));
            }
            // the predicate costs DB queries: only ask for runs this user is subscribed to
            if (!subscribed) {
                return;
            }
            boolean allowed;
            try {
                allowed = runStillAllowed.test(targetOf(runId));
            } catch (RuntimeException e) {
                // fail closed for this run only; the remaining runs are still checked
                log.warn("Run access revalidation failed for run {}, dropping the subscription: {}",
                        runId, e.getClass().getSimpleName());
                allowed = false;
            }
            if (allowed) {
                return;
            }
            List<Subscription> own;
            synchronized (stream) {
                own = stream.subscribers.stream().filter(s -> username.equals(s.username)).toList();
            }
            for (Subscription s : own) {
                try {
                    unsubscribe(s.sessionId, s.subscriptionId);
                } catch (RuntimeException e) {
                    // one failing item must not leave the user's remaining subscriptions on this run untouched
                    log.warn("Dropping a run output subscription failed: {}", e.getClass().getSimpleName());
                }
            }
        });
    }

    /**
     * Re-asks every subscriber of every run, for changes that name no user (a deleted deployment takes its grants
     * with it without a {@code UserAccessChangedEvent}). Fails closed per user and run.
     */
    void revalidateAll(java.util.function.BiPredicate<String, Optional<RunTarget>> stillAllowed) {
        List<String> users;
        synchronized (streams) {
            users = streams.values().stream().flatMap(stream -> {
                synchronized (stream) {
                    return stream.subscribers.stream().map(Subscription::username).toList().stream();
                }
            }).distinct().toList();
        }
        for (String user : users) {
            revalidate(user, target -> stillAllowed.test(user, target));
        }
    }

    private void removeIf(Predicate<Subscription> match) {
        List<Stream> all;
        synchronized (streams) {
            all = new ArrayList<>(streams.values());
        }
        for (Stream stream : all) {
            synchronized (stream) {
                stream.subscribers.removeIf(match);
            }
        }
    }

    private void send(String runId, Subscription sub, StreamMessage message) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sub.sessionId);
        accessor.setSubscriptionId(sub.subscriptionId);
        accessor.setDestination(TOPIC_PREFIX + runId);
        accessor.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
        accessor.setLeaveMutable(true);
        byte[] payload = mapper.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
        clientOutbound.send(MessageBuilder.createMessage(payload, accessor.getMessageHeaders()));
    }

    /** Drops finished streams past retention and, when full, the oldest finished ones. */
    private void evictExpired() {
        Instant cutoff = clock.instant().minus(properties.retention());
        streams.values().removeIf(s -> s.finishedAt != null && s.finishedAt.isBefore(cutoff));
        while (streams.size() >= properties.maxStreams()) {
            String oldest = streams.entrySet().stream()
                    .filter(e -> e.getValue().finishedAt != null)
                    .min(java.util.Comparator.comparing(e -> e.getValue().finishedAt))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest == null) {
                return;
            }
            streams.remove(oldest);
        }
    }
}
