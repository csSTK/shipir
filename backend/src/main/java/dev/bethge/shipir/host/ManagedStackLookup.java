package dev.bethge.shipir.host;

import java.util.Map;
import java.util.UUID;

/** Port implemented by compose/: ids of the stacks Shipir manages on a host, by stack name. */
public interface ManagedStackLookup {

    Map<String, UUID> managedStackIds(UUID hostId);
}
