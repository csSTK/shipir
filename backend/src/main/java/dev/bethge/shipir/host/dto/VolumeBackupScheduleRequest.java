package dev.bethge.shipir.host.dto;

/** Create a schedule: back up {@code volumeName} every {@code intervalHours} (1-8760), keep the newest {@code keepCount} (1-100). */
public record VolumeBackupScheduleRequest(String volumeName, Integer intervalHours, Integer keepCount) {
}
