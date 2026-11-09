package dev.bethge.shipir.proxy.status;

import dev.bethge.shipir.docker.DockerConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *       error) for at most {@code maxStale}, then {@link ProxyFacts#EMPTY}, so an old outage cannot keep showing an
 *       outdated result forever. {@code get} never throws. Failed loads are logged with class and code only.</li>
 * </ul>
 */
class StatusCache {

    private static final Logger log = LoggerFactory.getLogger(StatusCache.class);

    private record Entry(ProxyFacts facts, Instant loadedAt) {
    }

    private final Clock clock;
    private final Duration ttl;
    private final Duration refreshFloor;
    private final Duration maxStale;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastAttempt = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    StatusCache(Clock clock, Duration ttl, Duration refreshFloor, Duration maxStale) {
        this.clock = clock;
        this.maxStale = maxStale;
        this.ttl = ttl;
        this.refreshFloor = refreshFloor;
    }

    /**
     * Forgets a host entirely: its facts, its load throttle and its lock object, so a removed host leaves nothing
     * behind. A load that was already running may still store its result afterwards; for a removed host that entry is
     * never read and is dropped with the next eviction.
     */
    void evict(UUID hostId) {
        synchronized (locks.computeIfAbsent(hostId, k -> new Object())) {
            entries.remove(hostId);
            lastAttempt.remove(hostId);
        }
        locks.remove(hostId);
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
            ProxyFacts fallback = e != null && Duration.between(e.loadedAt(), now).compareTo(maxStale) < 0
                    ? e.facts() : ProxyFacts.EMPTY;
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
                // class and code only: the message may carry host details
                log.warn("Proxy status of host {} could not be loaded: {}{}", hostId, ex.getClass().getSimpleName(),
                        ex instanceof DockerConnectionException d ? " (" + d.getCode() + ")" : "");
                return fallback;
            }
        }
    }
}
