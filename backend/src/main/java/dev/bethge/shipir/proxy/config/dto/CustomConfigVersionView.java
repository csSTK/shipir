package dev.bethge.shipir.proxy.config.dto;

import java.time.Instant;
import java.util.UUID;

/** A backup of the custom file: the content a later change replaced, with who made that change and when. */
public record CustomConfigVersionView(UUID id, Instant createdAt, String createdBy, String content) {
}
