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
        Deque<Instant> window = sent.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && !window.peekFirst().isAfter(now.minus(WINDOW))) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                throw new ChatSessionException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Too many messages, try again in a minute");
            }
            window.addLast(now);
        }
    }
}
