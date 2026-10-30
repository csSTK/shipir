package dev.bethge.shipir.proxy.status;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StatusCacheTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    final MutableClock clock = new MutableClock();
    final StatusCache cache = new StatusCache(clock, Duration.ofSeconds(30), Duration.ofSeconds(5));
    final AtomicInteger loads = new AtomicInteger();
    final UUID host = UUID.randomUUID();

    ProxyFacts load() {
        loads.incrementAndGet();
        return new ProxyFacts(List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void servesFromCacheWithinTheTtlAndReloadsAfterwards() {
        cache.get(host, false, this::load);
        clock.now = clock.now.plusSeconds(29);
        cache.get(host, false, this::load);
        assertThat(loads).hasValue(1);

        clock.now = clock.now.plusSeconds(2);
        cache.get(host, false, this::load);
        assertThat(loads).hasValue(2);
    }

    @Test
    void refreshBypassesTheCacheButNotMoreOftenThanTheFloor() {
        cache.get(host, false, this::load);
        clock.now = clock.now.plusSeconds(2);
        cache.get(host, true, this::load);
        assertThat(loads).as("within the floor").hasValue(1);

        clock.now = clock.now.plusSeconds(4);
        cache.get(host, true, this::load);
        assertThat(loads).hasValue(2);
    }

    @Test
    void hostsAreCachedSeparately() {
        cache.get(host, false, this::load);
        cache.get(UUID.randomUUID(), false, this::load);
        assertThat(loads).hasValue(2);
    }

    @Test
    void failingLoadsAreAttemptedOncePerRefreshFloorAndNeverThrow() {
        AtomicInteger attempts = new AtomicInteger();
        java.util.function.Supplier<ProxyFacts> failing = () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("docker down");
        };

        for (int i = 0; i < 3; i++) {
            assertThat(cache.get(host, false, failing)).isSameAs(ProxyFacts.EMPTY);
            clock.now = clock.now.plusSeconds(1);
        }
        assertThat(attempts).as("three requests within the floor").hasValue(1);

        clock.now = clock.now.plusSeconds(5);
        cache.get(host, true, failing);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void lastGoodFactsAreServedWhenAReloadFails() {
        ProxyFacts good = cache.get(host, false, this::load);
        clock.now = clock.now.plusSeconds(31);

        ProxyFacts afterFailure = cache.get(host, false, () -> { throw new IllegalStateException("docker down"); });

        assertThat(afterFailure).isSameAs(good);
    }

    @Test
    void degradedFactsLiveOnlyAsLongAsTheRefreshFloor() {
        java.util.function.Supplier<ProxyFacts> degraded = () -> {
            loads.incrementAndGet();
            return new ProxyFacts(List.of(), List.of(), List.of(), List.of(), true);
        };
        cache.get(host, false, degraded);
        clock.now = clock.now.plusSeconds(4);
        cache.get(host, false, degraded);
        assertThat(loads).hasValue(1);

        clock.now = clock.now.plusSeconds(2);
        cache.get(host, false, degraded);
        assertThat(loads).hasValue(2);
    }
}
