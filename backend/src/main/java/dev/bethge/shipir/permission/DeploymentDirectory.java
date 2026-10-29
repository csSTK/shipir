package dev.bethge.shipir.permission;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Port implemented by compose/: which host owns a managed deployment, and which deployments a host has. */
public interface DeploymentDirectory {

    Optional<UUID> hostIdOf(UUID deploymentId);

    Set<UUID> deploymentIdsOf(UUID hostId);

    /** Names of the given deployments in one query; unknown ids are absent from the map. */
    Map<UUID, String> namesOf(Collection<UUID> deploymentIds);
}
