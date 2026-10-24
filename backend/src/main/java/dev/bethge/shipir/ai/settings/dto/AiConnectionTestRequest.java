package dev.bethge.shipir.ai.settings.dto;

import dev.bethge.shipir.ai.settings.AiProvider;

/** Optional POST /test body; no body or null provider tests the active provider. */
public record AiConnectionTestRequest(AiProvider provider) {
}
