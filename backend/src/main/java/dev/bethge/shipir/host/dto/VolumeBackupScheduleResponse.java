package dev.bethge.shipir.host.dto;

import java.time.Instant;
import java.util.UUID;

/** {@code lastStatus} is SUCCESS or FAILED, null before the first run. */
public record VolumeBackupScheduleResponse(UUID id, String volumeName, int intervalHours, int keepCount,
                                           Instant nextRunAt, Instant lastRunAt, String lastStatus) {
}
