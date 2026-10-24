package dev.bethge.shipir.config_editor.dto;

import java.time.Instant;

public record ConfigFileResponse(String path, String content, long size, Instant modifiedAt, boolean readOnly) {
}
