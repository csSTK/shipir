package dev.bethge.shipir.host;

import java.util.Map;
import java.util.UUID;

/** Port implemented by compose/: ids of the deployments Shipir manages on a host, by deployment name. */
public interface ManagedDeploymentLookup {

    Map<String, UUID> managedDeploymentIds(UUID hostId);
}
