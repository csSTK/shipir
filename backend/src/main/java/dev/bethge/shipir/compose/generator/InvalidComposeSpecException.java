package dev.bethge.shipir.compose.generator;

import java.util.List;

/** The wizard model is invalid; carries every violation found. Messages never echo secret values. */
public class InvalidComposeSpecException extends RuntimeException {

    private final List<String> violations;

    public InvalidComposeSpecException(List<String> violations) {
        super("Invalid compose specification: " + violations.size() + " violation(s)");
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
