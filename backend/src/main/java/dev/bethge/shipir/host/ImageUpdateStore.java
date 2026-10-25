package dev.bethge.shipir.host;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory result of the last update check per host: the tagged references with a newer remote digest. */
@Component
class ImageUpdateStore implements ImageUpdates {

    private record Result(Set<String> updatable, Instant checkedAt) {
    }

    private final Map<UUID, Result> results = new ConcurrentHashMap<>();

    void replace(UUID hostId, Set<String> updatableKeys, Instant checkedAt) {
        results.put(hostId, new Result(Set.copyOf(updatableKeys), checkedAt));
    }

    boolean isEmpty(UUID hostId) {
        Result r = results.get(hostId);
        return r == null || r.updatable().isEmpty();
    }

    /** True when the tagged reference ({@link ImageReference#key()}) has a newer remote digest. */
    @Override
    public boolean hasUpdate(UUID hostId, String key) {
        Result r = results.get(hostId);
        return r != null && r.updatable().contains(key);
    }

    @Override
    public Instant checkedAt(UUID hostId) {
        Result r = results.get(hostId);
        return r == null ? null : r.checkedAt();
    }

    void retainHosts(Set<UUID> hostIds) {
        results.keySet().retainAll(hostIds);
    }
}
