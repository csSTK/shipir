package dev.bethge.shipir.config_editor.dto;

import java.time.Instant;

/** One entry of the file tree; {@code path} is relative to the deployment directory with {@code /} separators. */
public record ConfigFileEntry(String path, boolean directory, long size, Instant modifiedAt, boolean readOnly) {
}
