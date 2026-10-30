package dev.bethge.shipir.ai.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rolling one-minute message limit per user (across all their chats), so one account cannot burn the provider
 * quota. State is in memory: a restart resets it, which is fine for an abuse brake.
 */
@Component
class ChatRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final int limit;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> sent = new ConcurrentHashMap<>();

    @Autowired
    ChatRateLimiter(ChatProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ChatRateLimiter(ChatProperties properties, Clock clock) {
        this.limit = properties.messagesPerMinute();
        this.clock = clock;
    }

    /** Counts one message for the user. @throws ChatSessionException 429 RATE_LIMITED when over the limit */
    void checkMessage(UUID userId) {
        Instant now = clock.instant();
        evictIdle(now);
        // compute and computeIfPresent are atomic per key, so eviction cannot drop a count that is being added
        sent.compute(userId, (id, existing) -> {
            Deque<Instant> window = existing == null ? new ArrayDeque<>() : existing;
            while (!window.isEmpty() && !window.peekFirst().isAfter(now.minus(WINDOW))) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                throw new ChatSessionException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Too many messages, try again in a minute");
            }
            window.addLast(now);
            return window;
        });
    }

    /** Drops users whose newest message left the window, so the map does not grow with every user ever seen. */
    private void evictIdle(Instant now) {
        Instant cutoff = now.minus(WINDOW);
        // the idle check runs inside computeIfPresent, i.e. under the same per-key lock as checkMessage's compute
        for (UUID id : sent.keySet()) {
            sent.computeIfPresent(id, (k, w) -> w.isEmpty() || !w.peekLast().isAfter(cutoff) ? null : w);
        }
    }

    int trackedUsers() {
        return sent.size();
    }
}
