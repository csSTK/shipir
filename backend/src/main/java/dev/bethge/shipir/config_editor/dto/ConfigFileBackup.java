package dev.bethge.shipir.config_editor.dto;

import java.time.Instant;

public record ConfigFileBackup(String id, Instant createdAt, long size) {
}
