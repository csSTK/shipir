package dev.bethge.shipir.docker.dto;

/**
 * One resource a prune would remove. {@code sizeBytes} is null when the daemon does not report a size
 * (volumes, networks); {@code composeProject} is the Compose project label or null.
 */
public record PruneCandidateDto(String id, String name, Long sizeBytes, String composeProject) {
}
