package dev.bethge.shipir.compose;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** At most one deploy, stop, rollback or delete per stack at a time (in-memory, single instance). */
@Component
class DeploymentLocks {

    private final Set<UUID> held = ConcurrentHashMap.newKeySet();

    /** @throws StackException 409 DEPLOYMENT_IN_PROGRESS if the stack is busy */
    void acquire(UUID stackId) {
        if (!held.add(stackId)) {
            throw new StackException(HttpStatus.CONFLICT, "DEPLOYMENT_IN_PROGRESS",
                    "Another deployment of this stack is still running");
        }
    }

    void release(UUID stackId) {
        held.remove(stackId);
    }
}
