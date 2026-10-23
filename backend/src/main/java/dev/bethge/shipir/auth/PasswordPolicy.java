package dev.bethge.shipir.auth;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Rules for a new password. Reports every violated rule as a stable {@link Violation} code so the UI can
 * show them all at once. Mirrored client-side for live feedback; this class is authoritative.
 *
 * <p>Length is {@link String#length()}. A symbol is any code point that is neither a Unicode letter nor a
 * decimal digit (spaces count). The username comparison ignores case.
 */
@Component
class PasswordPolicy {

    static final int MIN_LENGTH = 15;
    static final int MAX_LENGTH = 128;

    enum Violation {
        TOO_SHORT, TOO_LONG, MISSING_UPPERCASE, MISSING_LOWERCASE, MISSING_DIGIT, MISSING_SYMBOL,
        EQUALS_USERNAME, SAME_AS_CURRENT
    }

    List<Violation> validate(String candidate, String username, String currentPassword) {
        List<Violation> violations = new ArrayList<>();
        if (candidate.length() < MIN_LENGTH) {
            violations.add(Violation.TOO_SHORT);
        }
        if (candidate.length() > MAX_LENGTH) {
            violations.add(Violation.TOO_LONG);
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean symbol = false;
        for (int i = 0; i < candidate.length(); ) {
            int cp = candidate.codePointAt(i);
            i += Character.charCount(cp);
            switch (Character.getType(cp)) {
                case Character.UPPERCASE_LETTER -> upper = true;
                case Character.LOWERCASE_LETTER -> lower = true;
                case Character.DECIMAL_DIGIT_NUMBER -> digit = true;
                default -> {
                    if (!Character.isLetter(cp)) {
                        symbol = true;
                    }
                }
            }
        }
        if (!upper) {
            violations.add(Violation.MISSING_UPPERCASE);
        }
        if (!lower) {
            violations.add(Violation.MISSING_LOWERCASE);
        }
        if (!digit) {
            violations.add(Violation.MISSING_DIGIT);
        }
        if (!symbol) {
            violations.add(Violation.MISSING_SYMBOL);
        }
        if (candidate.equalsIgnoreCase(username)) {
            violations.add(Violation.EQUALS_USERNAME);
        }
        if (candidate.equals(currentPassword)) {
            violations.add(Violation.SAME_AS_CURRENT);
        }
        return violations;
    }
}
