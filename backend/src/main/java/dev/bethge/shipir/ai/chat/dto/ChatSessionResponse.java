package dev.bethge.shipir.ai.chat.dto;

import java.time.Instant;
import java.util.UUID;

/** {@code ownerUsername} is only filled for the ADMIN view of shared sessions. */
public record ChatSessionResponse(UUID id, String title, boolean sharedWithAdmin, Instant createdAt,
                                  Instant updatedAt, String ownerUsername) {
}
