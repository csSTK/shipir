package dev.bethge.shipir.compose.validation;

import java.util.List;

/** Outcome of {@code docker compose config --quiet}: error lines are empty when valid. */
public record ValidationResult(boolean valid, List<String> errors) {

    public ValidationResult {
        errors = List.copyOf(errors);
    }
}
