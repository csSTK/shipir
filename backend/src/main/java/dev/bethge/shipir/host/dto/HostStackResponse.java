package dev.bethge.shipir.host.dto;

import dev.bethge.shipir.docker.dto.StackSummaryDto;

import java.util.UUID;

/** A Compose project on a host; {@code managedStackId} is set when Shipir manages it. */
public record HostStackResponse(
        String name,
        int containerCount,
        int runningCount,
        StackSummaryDto.Status status,
        String workingDir,
        boolean managed,
        UUID managedStackId) {

    public static HostStackResponse of(StackSummaryDto s, UUID managedStackId) {
        return new HostStackResponse(s.name(), s.containerCount(), s.runningCount(), s.status(), s.workingDir(),
                managedStackId != null, managedStackId);
    }
}
