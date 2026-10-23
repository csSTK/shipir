package dev.bethge.shipir.ai.settings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** PUT body. {@code apiKey}: null keeps the stored key, an empty string clears it. */
public record AiSettingsRequest(@NotBlank @Size(max = 2048) String baseUrl,
                                @NotBlank @Size(max = 255) String model,
                                @NotNull Boolean enabled,
                                @Size(max = 1024) String apiKey) {

    /** The key is left out. */
    @Override
    public String toString() {
        return "AiSettingsRequest[baseUrl=" + baseUrl + ", model=" + model + ", enabled=" + enabled
                + ", apiKey=<hidden>]";
    }
}
