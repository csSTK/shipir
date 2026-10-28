package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.compose.DeploymentService;
import dev.bethge.shipir.compose.dto.EnvEntry;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads the stored (decrypted) env of the deployment named by {@code hostId} and {@code deploymentId}. */
@Component
class StoredDeploymentEnvValues implements DeploymentEnvValues {

    private final DeploymentService deployments;

    StoredDeploymentEnvValues(DeploymentService deployments) {
        this.deployments = deployments;
    }

    @Override
    public Collection<String> valuesFor(Map<String, Object> args) {
        if (!(args.get("hostId") instanceof String host) || !(args.get("deploymentId") instanceof String deployment)) {
            return List.of(); // the call names no deployment: nothing to redact
        }
        try {
            return deployments.env(UUID.fromString(host), UUID.fromString(deployment)).entries().stream()
                    .map(EnvEntry::value).toList();
        } catch (RuntimeException e) {
            throw new Unavailable(e);
        }
    }
}
