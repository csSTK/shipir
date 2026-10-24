package dev.bethge.shipir.ai.settings.dto;

import dev.bethge.shipir.ai.settings.AiProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * PUT body. Sets the global switch and the active provider and updates the stored config of {@code provider}
 * (defaults to the active provider). {@code apiKey}: null keeps the provider's stored key, an empty string clears it.
 */
public record AiSettingsRequest(@NotNull Boolean enabled,
                                @NotNull AiProvider activeProvider,
                                AiProvider provider,
                                @NotBlank @Size(max = 2048) String baseUrl,
                                @NotBlank @Size(max = 255) String model,
                                @Size(max = 1024) String apiKey) {

    /** The key is left out. */
    @Override
    public String toString() {
        return "AiSettingsRequest[enabled=" + enabled + ", activeProvider=" + activeProvider + ", provider=" + provider
                + ", baseUrl=" + baseUrl + ", model=" + model + ", apiKey=<hidden>]";
    }
}
