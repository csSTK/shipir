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
    final StatusCache cache = new StatusCache(clock, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMinutes(5));
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

    @Test
    void staleFactsAreNotServedBeyondTheMaximumAge() {
        StatusCache capped = new StatusCache(clock, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMinutes(5));
        ProxyFacts good = capped.get(host, false, this::load);
        java.util.function.Supplier<ProxyFacts> failing = () -> { throw new IllegalStateException("docker down"); };

        clock.now = clock.now.plusSeconds(120);
        assertThat(capped.get(host, false, failing)).as("still within the cap").isSameAs(good);

        clock.now = clock.now.plusSeconds(200);
        assertThat(capped.get(host, false, failing)).as("older than the cap").isSameAs(ProxyFacts.EMPTY);
    }

    @Test
    void aFailedLoadIsLoggedWithClassAndCodeButNotTheMessage() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(StatusCache.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            cache.get(host, false, () -> {
                throw new dev.bethge.shipir.docker.DockerConnectionException(
                        dev.bethge.shipir.docker.DockerErrorCode.UNREACHABLE, "secret-detail-token", null);
            });
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        String text = appender.list.get(0).getFormattedMessage();
        assertThat(text).contains("DockerConnectionException").contains("UNREACHABLE").contains(host.toString())
                .doesNotContain("secret-detail-token");
    }
}
