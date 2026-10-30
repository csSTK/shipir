package dev.bethge.shipir.proxy.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Short per-host cache of what was read from the proxy.
 * <ul>
 *   <li>Complete facts live for {@code ttl}, degraded (partial) facts only for {@code refreshFloor}.</li>
 *   <li>A caller may ask for a refresh, but not more often than {@code refreshFloor}.</li>
 *   <li>Load attempts are throttled to one per {@code refreshFloor} per host, failed ones included, so an outage
 *       does not turn every request into a Docker round trip. On failure the last good facts are served (stale while
 *       error), or {@link ProxyFacts#EMPTY} if there never were any. {@code get} never throws.</li>
 * </ul>
 */
class StatusCache {

    private record Entry(ProxyFacts facts, Instant loadedAt) {
    }

    private final Clock clock;
    private final Duration ttl;
    private final Duration refreshFloor;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastAttempt = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    StatusCache(Clock clock, Duration ttl, Duration refreshFloor) {
        this.clock = clock;
        this.ttl = ttl;
        this.refreshFloor = refreshFloor;
    }

    ProxyFacts get(UUID hostId, boolean refresh, Supplier<ProxyFacts> loader) {
        synchronized (locks.computeIfAbsent(hostId, k -> new Object())) {
            Instant now = clock.instant();
            Entry e = entries.get(hostId);
            if (e != null) {
                Duration age = Duration.between(e.loadedAt(), now);
                Duration lifetime = e.facts().degraded() ? refreshFloor : ttl;
                boolean mayRefresh = refresh && age.compareTo(refreshFloor) >= 0;
                if (age.compareTo(lifetime) < 0 && !mayRefresh) {
                    return e.facts();
                }
            }
            ProxyFacts fallback = e != null ? e.facts() : ProxyFacts.EMPTY;
            Instant attempted = lastAttempt.get(hostId);
            if (attempted != null && Duration.between(attempted, now).compareTo(refreshFloor) < 0) {
                return fallback;
            }
            lastAttempt.put(hostId, now);
            try {
                ProxyFacts facts = loader.get();
                entries.put(hostId, new Entry(facts, now));
                return facts;
            } catch (RuntimeException ex) {
                return fallback;
            }
        }
    }
}
