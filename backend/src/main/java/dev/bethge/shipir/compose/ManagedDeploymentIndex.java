package dev.bethge.shipir.compose;

import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.host.ManagedDeploymentLookup;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

/** Tells host/ which discovered Compose projects are managed by Shipir. */
@Component
class ManagedDeploymentIndex implements ManagedDeploymentLookup {

    private final DeploymentRepository deployments;
    private final ComposeFileRepository files;

    ManagedDeploymentIndex(DeploymentRepository deployments, ComposeFileRepository files) {
        this.deployments = deployments;
        this.files = files;
    }

    @Override
    public Map<String, UUID> managedDeploymentIds(UUID hostId) {
        Map<String, UUID> ids = new HashMap<>();
        deployments.findByHostIdOrderByNameAsc(hostId).forEach(s -> ids.put(s.getName(), s.getId()));
        return ids;
    }

    @Override
    public Set<String> systemManagedNames(UUID hostId) {
        return deployments.findByHostIdOrderByNameAsc(hostId).stream().filter(Deployment::isSystemManaged)
                .map(Deployment::getName).collect(Collectors.toSet());
    }

    @Override
    public List<String> systemManagedComposeFiles(UUID hostId) {
        return deployments.findByHostIdOrderByNameAsc(hostId).stream().filter(Deployment::isSystemManaged)
                .flatMap(d -> files.readCompose(hostId, d.getName()).stream()).toList();
    }
}
