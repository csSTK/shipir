package dev.bethge.shipir.compose.dto;

import dev.bethge.shipir.compose.StackSource;
import dev.bethge.shipir.compose.StackStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Stack with its current YAML; env values are never included, only the variable names. */
public record StackDetailResponse(UUID id, String name, StackStatus status, StackSource source,
                                  Instant lastDeployedAt, Instant createdAt, String yaml, List<String> envKeys) {
}
