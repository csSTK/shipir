package dev.bethge.shipir.compose.dto;

import dev.bethge.shipir.compose.VersionSource;

import java.time.Instant;
import java.util.UUID;

/** One entry of a deployment's version history; content (YAML, env) is not included. {@code deployed=false} marks the editable draft; {@code current} is the latest successfully deployed version (none if no deploy succeeded). */
public record DeploymentVersionResponse(UUID id, VersionSource source, String commitSha, String createdBy,
                                   Instant createdAt, boolean current, boolean deployed) {
}
