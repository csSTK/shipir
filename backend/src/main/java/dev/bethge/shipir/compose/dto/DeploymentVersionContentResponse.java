package dev.bethge.shipir.compose.dto;

import dev.bethge.shipir.compose.VersionSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Content of one version for diffing: YAML, env keys with a {@code changed} flag against the baseline (the currently
 * deployed version) and keys only the baseline has. Never contains env values or hashes. {@code baselineVersionId} is
 * null when nothing is deployed yet (all {@code changed} false, {@code removedKeys} empty).
 */
public record DeploymentVersionContentResponse(UUID id, VersionSource source, String commitSha, String createdBy,
                                               Instant createdAt, boolean current, boolean deployed, String yaml,
                                               UUID baselineVersionId, List<EnvKey> env, List<String> removedKeys) {

    /** {@code changed}: key is new or its value differs from the baseline's. */
    public record EnvKey(String key, boolean changed) {
    }
}
