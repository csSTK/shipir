package dev.bethge.shipir.permission.dto;

import java.time.Instant;
import java.util.UUID;

public record TeamResponse(UUID id, String name, long memberCount, Instant createdAt) {
}
