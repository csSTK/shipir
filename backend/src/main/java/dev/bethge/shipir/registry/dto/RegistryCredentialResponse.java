package dev.bethge.shipir.registry.dto;

import java.time.Instant;
import java.util.UUID;

/** Never carries the secret, only {@code secretHint}. */
public record RegistryCredentialResponse(UUID id, String name, String registryHost, String username,
                                         String secretHint, Instant createdAt, Instant updatedAt) {
}
