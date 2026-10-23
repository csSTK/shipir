package dev.bethge.shipir.compose;

import dev.bethge.shipir.host.ManagedStackLookup;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tells host/ which discovered Compose projects are managed by Shipir. */
@Component
class ManagedStackIndex implements ManagedStackLookup {

    private final StackRepository stacks;

    ManagedStackIndex(StackRepository stacks) {
        this.stacks = stacks;
    }

    @Override
    public Map<String, UUID> managedStackIds(UUID hostId) {
        Map<String, UUID> ids = new HashMap<>();
        stacks.findByHostIdOrderByNameAsc(hostId).forEach(s -> ids.put(s.getName(), s.getId()));
        return ids;
    }
}
