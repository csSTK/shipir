package dev.bethge.shipir.compose;

import dev.bethge.shipir.host.ManagedDeploymentLookup;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tells host/ which discovered Compose projects are managed by Shipir. */
@Component
class ManagedDeploymentIndex implements ManagedDeploymentLookup {

    private final DeploymentRepository deployments;

    ManagedDeploymentIndex(DeploymentRepository deployments) {
        this.deployments = deployments;
    }

    @Override
    public Map<String, UUID> managedDeploymentIds(UUID hostId) {
        Map<String, UUID> ids = new HashMap<>();
        deployments.findByHostIdOrderByNameAsc(hostId).forEach(s -> ids.put(s.getName(), s.getId()));
        return ids;
    }
}
