package dev.bethge.shipir.compose.dto;

import dev.bethge.shipir.compose.StackSource;
import dev.bethge.shipir.compose.StackStatus;

import java.time.Instant;
import java.util.UUID;

public record StackSummaryResponse(UUID id, String name, StackStatus status, StackSource source,
                                   Instant lastDeployedAt, Instant createdAt) {
}
