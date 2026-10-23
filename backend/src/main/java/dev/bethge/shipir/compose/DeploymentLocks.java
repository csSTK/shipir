package dev.bethge.shipir.compose;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** At most one deploy, stop, rollback or delete per deployment at a time (in-memory, single instance). */
@Component
class DeploymentLocks {

    private final Set<UUID> held = ConcurrentHashMap.newKeySet();

    /** @throws DeploymentException 409 DEPLOYMENT_IN_PROGRESS if the deployment is busy */
    void acquire(UUID deploymentId) {
        if (!held.add(deploymentId)) {
            throw new DeploymentException(HttpStatus.CONFLICT, "DEPLOYMENT_IN_PROGRESS",
                    "Another run of this deployment is still in progress");
        }
    }

    void release(UUID deploymentId) {
        held.remove(deploymentId);
    }
}
