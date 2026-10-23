package dev.bethge.shipir.ai.settings.dto;

/** The key itself is never returned, only a masked hint. */
public record AiSettingsResponse(String baseUrl, String model, boolean enabled, boolean keyConfigured,
                                 String apiKeyHint) {
}
