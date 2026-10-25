package dev.bethge.shipir.docker.dto;

/**
 * New limits for a running or stopped container, already validated by the caller. A null field is left unchanged,
 * {@code 0} removes the limit. {@code memoryBytes}: RAM limit in bytes (swap is set to the same value, i.e. none).
 * {@code nanoCpus}: CPU limit in billionths of a CPU. {@code restartPolicy}: no, always, unless-stopped, on-failure.
 */
public record ContainerResourcesSpec(Long memoryBytes, Long nanoCpus, String restartPolicy) {
}
