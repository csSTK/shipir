package dev.bethge.shipir.host.dto;

import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;

import java.util.UUID;

/** A Compose project on a host; {@code managedDeploymentId} is set when Shipir manages it. */
public record HostDeploymentResponse(
        String name,
        int containerCount,
        int runningCount,
        DeploymentSummaryDto.Status status,
        String workingDir,
        boolean managed,
        UUID managedDeploymentId) {

    public static HostDeploymentResponse of(DeploymentSummaryDto s, UUID managedDeploymentId) {
        return new HostDeploymentResponse(s.name(), s.containerCount(), s.runningCount(), s.status(), s.workingDir(),
                managedDeploymentId != null, managedDeploymentId);
    }
}
