package dev.bethge.shipir.host.dto;

import java.time.Instant;

/** One archive written by a scheduled backup; {@code createdAt} is the timestamp encoded in the file name (UTC). */
public record StoredVolumeBackup(String name, long sizeBytes, Instant createdAt) {
}
