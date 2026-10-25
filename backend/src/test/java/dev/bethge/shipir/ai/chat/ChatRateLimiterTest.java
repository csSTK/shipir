package dev.bethge.shipir.ai.chat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRateLimiterTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-25T10:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final ChatRateLimiter limiter = new ChatRateLimiter(new ChatProperties(8, 2, java.time.Duration.ofSeconds(30), 2, 3, 3, 40, 60000), clock);

    @Test
    void rejectsTheMessageOverTheLimitWith429() {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            limiter.checkMessage(user);
        }
        assertThatThrownBy(() -> limiter.checkMessage(user)).isInstanceOfSatisfying(ChatSessionException.class, e -> {
            assertThat(e.code()).isEqualTo("RATE_LIMITED");
            assertThat(e.status().value()).isEqualTo(429);
        });
    }

    @Test
    void allowsMessagesAgainOnceTheMinuteHasPassed() {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            limiter.checkMessage(user);
        }
        clock.now = clock.now.plus(Duration.ofSeconds(61));
        limiter.checkMessage(user);
    }

    @Test
    void limitsAreCountedPerUser() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            limiter.checkMessage(a);
        }
        limiter.checkMessage(b);
    }
}
