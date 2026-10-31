package dev.bethge.shipir.host;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Port implemented by compose/: ids of the deployments Shipir manages on a host, by deployment name. */
public interface ManagedDeploymentLookup {

    Map<String, UUID> managedDeploymentIds(UUID hostId);

    /** Names of the managed deployments on a host that are system-managed (the managed proxy): ADMIN-only secrets. */
    default Set<String> systemManagedNames(UUID hostId) {
        return Set.of();
    }
}
