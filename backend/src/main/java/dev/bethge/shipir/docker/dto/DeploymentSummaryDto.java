package dev.bethge.shipir.docker.dto;

/** A Compose project found on a host, derived from container labels. {@code workingDir} may be null. */
public record DeploymentSummaryDto(
        String name,
        int containerCount,
        int runningCount,
        Status status,
        String workingDir) {

    /** RUNNING: all containers running, STOPPED: none, PARTIAL: some. */
    public enum Status { RUNNING, PARTIAL, STOPPED }
}
