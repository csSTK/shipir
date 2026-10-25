package dev.bethge.shipir.host.dto;

/**
 * Change limits of a container in place. A null field stays unchanged, {@code 0} removes the limit. {@code memoryBytes}
 * is 0 or at least 6 MiB; {@code cpus} is 0 or 0.01 to 1024; {@code restartPolicy}: no, always, unless-stopped,
 * on-failure. At least one field is required.
 */
public record UpdateContainerResourcesRequest(Long memoryBytes, Double cpus, String restartPolicy) {
}
