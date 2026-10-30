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

    @Test
    void evictsIdleUsersSoTheMapDoesNotGrowForever() {
        for (int i = 0; i < 50; i++) {
            limiter.checkMessage(UUID.randomUUID());
        }
        assertThat(limiter.trackedUsers()).isEqualTo(50);
        clock.now = clock.now.plus(Duration.ofSeconds(61));
        limiter.checkMessage(UUID.randomUUID());
        assertThat(limiter.trackedUsers()).isEqualTo(1);
    }

    @Test
    void activeUsersKeepTheirCountWhenOthersAreEvicted() {
        UUID busy = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            limiter.checkMessage(busy);
        }
        clock.now = clock.now.plus(Duration.ofSeconds(30));
        for (int i = 0; i < 50; i++) {
            limiter.checkMessage(UUID.randomUUID());
        }
        assertThatThrownBy(() -> limiter.checkMessage(busy)).isInstanceOf(ChatSessionException.class);
    }

    @Test
    void concurrentChecksNeverLoseACountWhileOtherUsersAreEvicted() throws Exception {
        ChatRateLimiter big = new ChatRateLimiter(new ChatProperties(8, 2, java.time.Duration.ofSeconds(30), 2, 400, 3, 40, 60000), clock);
        UUID busy = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
        for (int t = 0; t < 8; t++) {
            fs.add(pool.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    big.checkMessage(UUID.randomUUID());
                    try {
                        big.checkMessage(busy);
                        accepted.incrementAndGet();
                    } catch (ChatSessionException ignored) {
                        // over the limit
                    }
                }
            }));
        }
        for (var f : fs) {
            f.get();
        }
        pool.shutdown();
        assertThat(accepted.get()).isEqualTo(400);
        assertThatThrownBy(() -> big.checkMessage(busy)).isInstanceOf(ChatSessionException.class);
    }
}
