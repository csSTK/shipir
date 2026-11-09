package dev.bethge.shipir.host;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Port implemented by compose/: ids of the deployments Shipir manages on a host, by deployment name. */
public interface ManagedDeploymentLookup {

    Map<String, UUID> managedDeploymentIds(UUID hostId);

    /**
     * Names of the managed deployments on a host that are system-managed (the managed proxy): ADMIN-only secrets.
     * Abstract on purpose: an implementation must decide this explicitly, it must not fail open to an empty set.
     */
    Set<String> systemManagedNames(UUID hostId);

    /**
     * Stored compose files of the system-managed deployments on a host. {@code host/} reads the images and networks
     * the proxy is configured with from them, so a customised or outdated proxy image stays protected while the proxy
     * is stopped. Defaults to none: it only adds protection on top of {@link #systemManagedNames}.
     */
    default List<String> systemManagedComposeFiles(UUID hostId) {
        return List.of();
    }
}
