package dev.bethge.shipir.compose;

import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.host.ManagedDeploymentLookup;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
    private final DeploymentVersionRepository versions;

    ManagedDeploymentIndex(DeploymentRepository deployments, ComposeFileRepository files,
                           DeploymentVersionRepository versions) {
        this.deployments = deployments;
        this.files = files;
        this.versions = versions;
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

    /**
     * The current stored compose and the yaml of every earlier version of each system-managed deployment (an image from
     * before a template bump is still pulled on the host), with {@code ${VAR}} resolved against the current {@code .env}.
     * The stored .env is plaintext on disk; encrypted env of old versions is not decrypted, so a variable that only an
     * old version defined stays unresolved.
     */
    @Override
    public List<String> systemManagedComposeFiles(UUID hostId) {
        List<String> result = new ArrayList<>();
        for (Deployment d : deployments.findByHostIdOrderByNameAsc(hostId)) {
            if (!d.isSystemManaged()) {
                continue;
            }
            Map<String, String> env = new HashMap<>();
            files.readEnv(hostId, d.getName()).ifPresent(text -> EnvFile.parse(text).forEach(e -> env.put(e.key(), e.value())));
            files.readCompose(hostId, d.getName()).ifPresent(yaml -> result.add(ComposeInterpolation.apply(yaml, env)));
            versions.findByDeploymentIdOrderByCreatedAtDesc(d.getId())
                    .forEach(v -> result.add(ComposeInterpolation.apply(v.getYamlContent(), env)));
        }
        return result;
    }
}
