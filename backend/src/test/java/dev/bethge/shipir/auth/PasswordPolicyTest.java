package dev.bethge.shipir.auth;

import dev.bethge.shipir.auth.PasswordPolicy.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    private static final String USER = "admin";
    private static final String CURRENT = "Current-Password-1!";
    private static final String VALID = "Abcdefghijkl1!x"; // exactly 15 chars

    private final PasswordPolicy policy = new PasswordPolicy();

    private List<Violation> check(String candidate) {
        return policy.validate(candidate, USER, CURRENT);
    }

    @Test
    void fifteenCharCompliantPasswordPasses() {
        assertThat(VALID).hasSize(15);
        assertThat(check(VALID)).isEmpty();
    }

    @Test
    void fourteenCharsOnlyTooShort() {
        assertThat(check("Abcdefghijk1!x")).containsExactly(Violation.TOO_SHORT);
    }

    @Test
    void maxLengthPassesAnd129OnlyTooLong() {
        String at128 = "Aa1!" + "b".repeat(124);
        assertThat(at128).hasSize(128);
        assertThat(check(at128)).isEmpty();
        assertThat(check(at128 + "b")).containsExactly(Violation.TOO_LONG);
    }

    @Test
    void missingUppercaseOnly() {
        assertThat(check("abcdefghijkl1!x")).containsExactly(Violation.MISSING_UPPERCASE);
    }

    @Test
    void missingLowercaseOnly() {
        assertThat(check("ABCDEFGHIJKL1!X")).containsExactly(Violation.MISSING_LOWERCASE);
    }

    @Test
    void missingDigitOnly() {
        assertThat(check("Abcdefghijklm!x")).containsExactly(Violation.MISSING_DIGIT);
    }

    @Test
    void missingSymbolOnly() {
        assertThat(check("Abcdefghijklm1x")).containsExactly(Violation.MISSING_SYMBOL);
    }

    @Test
    void equalsUsernameIgnoringCaseOnly() {
        String candidate = "Administrator-Account1!";
        assertThat(policy.validate(candidate, candidate.toLowerCase(), CURRENT))
                .containsExactly(Violation.EQUALS_USERNAME);
        assertThat(policy.validate(candidate, candidate.toUpperCase(), CURRENT))
                .containsExactly(Violation.EQUALS_USERNAME);
    }

    @Test
    void sameAsCurrentOnly() {
        assertThat(policy.validate(CURRENT, USER, CURRENT)).containsExactly(Violation.SAME_AS_CURRENT);
    }

    @Test
    void multipleViolationsAreAllReported() {
        assertThat(check("abc")).containsExactlyInAnyOrder(Violation.TOO_SHORT, Violation.MISSING_UPPERCASE,
                Violation.MISSING_DIGIT, Violation.MISSING_SYMBOL);
    }

    @Test
    void unicodeLettersAndSymbolsAreRecognised() {
        // Ä upper, ö lower, Arabic-Indic digit, euro sign as symbol
        assertThat(check("Äöäöäöäöäöäöäö٣€")).isEmpty();
        // Cyrillic-only letters with digit and symbol
        assertThat(check("Пароль-Пароль-1ъъъ")).isEmpty();
    }

    @Test
    void spaceCountsAsSymbol() {
        assertThat(check("Abcdefghijkl1 xy")).isEmpty();
    }

    @Test
    void lengthCountsJavaStringLength() {
        // 7 emoji = 14 UTF-16 units, plus 'A' = 15 units but only 8 code points
        String s = "A" + "😀".repeat(7);
        assertThat(s.length()).isEqualTo(15);
        assertThat(check(s)).doesNotContain(Violation.TOO_SHORT);
    }
}
