package dev.bethge.shipir.permission;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Port implemented by compose/: which host owns a managed deployment, and which deployments a host has. */
public interface DeploymentDirectory {

    Optional<UUID> hostIdOf(UUID deploymentId);

    Set<UUID> deploymentIdsOf(UUID hostId);
}
