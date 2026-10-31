package dev.bethge.shipir.host.dto;

import dev.bethge.shipir.docker.dto.DeploymentSummaryDto;

import java.util.UUID;

/**
 * A Compose project on a host; {@code managedDeploymentId} is set when Shipir manages it. {@code systemManaged}: it is the
 * managed proxy deployment, operable by ADMIN only (the UI labels it as managed via Domains).
 */
public record HostDeploymentResponse(
        String name,
        int containerCount,
        int runningCount,
        DeploymentSummaryDto.Status status,
        String workingDir,
        boolean managed,
        UUID managedDeploymentId,
        boolean updateAvailable,
        boolean systemManaged) {

    public static HostDeploymentResponse of(DeploymentSummaryDto s, UUID managedDeploymentId) {
        return of(s, managedDeploymentId, false, false);
    }

    /** {@code updateAvailable}: a container of the project runs an image with a newer remote digest. */
    public static HostDeploymentResponse of(DeploymentSummaryDto s, UUID managedDeploymentId, boolean updateAvailable,
                                            boolean systemManaged) {
        return new HostDeploymentResponse(s.name(), s.containerCount(), s.runningCount(), s.status(), s.workingDir(),
                managedDeploymentId != null, managedDeploymentId, updateAvailable, systemManaged);
    }
}
