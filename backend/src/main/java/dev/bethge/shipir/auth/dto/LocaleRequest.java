package dev.bethge.shipir.auth.dto;

import jakarta.validation.constraints.Pattern;

/** {@code locale} null clears the choice (the frontend then follows the browser language). */
public record LocaleRequest(@Pattern(regexp = "en|de") String locale) {
}
