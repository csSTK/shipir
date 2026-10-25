package dev.bethge.shipir.host;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Reads and replaces the tag sets of environments. Inputs must already be normalised by {@link HostTagPolicy}. */
@Service
class HostTagService {

    private final HostTagRepository repository;

    HostTagService(HostTagRepository repository) {
        this.repository = repository;
    }

    /** Sorted tags per host; hosts without tags are absent from the map. */
    @Transactional(readOnly = true)
    Map<UUID, List<String>> tagsByHost(Collection<UUID> hostIds) {
        Map<UUID, List<String>> result = new HashMap<>();
        if (hostIds.isEmpty()) {
            return result;
        }
        for (HostTag tag : repository.findByHostIdIn(hostIds)) {
            result.computeIfAbsent(tag.getHostId(), id -> new ArrayList<>()).add(tag.getTag());
        }
        result.values().forEach(list -> list.sort(null));
        return result;
    }

    @Transactional(readOnly = true)
    List<String> tagsOf(UUID hostId) {
        return repository.findByHostId(hostId).stream().map(HostTag::getTag).sorted().toList();
    }

    /**
     * Makes the stored tag set of one host equal to {@code desired}: removes what is no longer wanted, adds what is
     * new. The two sets are disjoint, so the inserts and deletes Hibernate flushes never clash on the unique key.
     * Joins the caller's transaction.
     */
    @Transactional
    void replace(UUID hostId, List<String> desired) {
        List<HostTag> existing = repository.findByHostId(hostId);
        Set<String> current = existing.stream().map(HostTag::getTag).collect(Collectors.toSet());
        Set<String> wanted = Set.copyOf(desired);
        repository.deleteAll(existing.stream().filter(t -> !wanted.contains(t.getTag())).toList());
        repository.saveAll(desired.stream().filter(t -> !current.contains(t))
                .map(t -> new HostTag(hostId, t)).toList());
        repository.flush();
    }
}
