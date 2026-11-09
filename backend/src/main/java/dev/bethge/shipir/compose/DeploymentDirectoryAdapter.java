package dev.bethge.shipir.compose;

import dev.bethge.shipir.permission.DeploymentDirectory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Answers permission/'s questions about managed deployments. */
@Component
class DeploymentDirectoryAdapter implements DeploymentDirectory {

    private final DeploymentRepository deployments;

    DeploymentDirectoryAdapter(DeploymentRepository deployments) {
        this.deployments = deployments;
    }

    @Override
    public Optional<UUID> hostIdOf(UUID deploymentId) {
        return deployments.findById(deploymentId).map(Deployment::getHostId);
    }

    @Override
    public Map<UUID, UUID> hostIdsOf(Collection<UUID> deploymentIds) {
        return deployments.findAllById(deploymentIds).stream()
                .collect(Collectors.toMap(Deployment::getId, Deployment::getHostId));
    }

    @Override
    public Set<UUID> deploymentIdsOf(UUID hostId) {
        return deployments.findByHostIdOrderByNameAsc(hostId).stream().map(Deployment::getId)
                .collect(Collectors.toSet());
    }

    @Override
    public Map<UUID, String> namesOf(Collection<UUID> deploymentIds) {
        return deployments.findAllById(deploymentIds).stream()
                .collect(Collectors.toMap(Deployment::getId, Deployment::getName));
    }
}
