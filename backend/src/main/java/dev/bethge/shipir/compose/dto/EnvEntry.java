package dev.bethge.shipir.compose.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One .env variable. {@code toString} hides the value. */
public record EnvEntry(@NotBlank @Size(max = 255) String key, @Size(max = 10000) String value) {

    @Override
    public String toString() {
        return "EnvEntry[key=" + key + ", value=***]";
    }
}
