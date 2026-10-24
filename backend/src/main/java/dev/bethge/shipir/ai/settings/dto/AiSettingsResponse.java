package dev.bethge.shipir.ai.settings.dto;

import dev.bethge.shipir.ai.settings.AiProvider;

import java.util.List;

/** Keys are never returned, only a masked hint per provider. */
public record AiSettingsResponse(AiProvider activeProvider, boolean enabled, List<ProviderSettings> providers) {

    public record ProviderSettings(AiProvider provider, String baseUrl, String model, boolean keyConfigured,
                                   String apiKeyHint) {
    }
}
