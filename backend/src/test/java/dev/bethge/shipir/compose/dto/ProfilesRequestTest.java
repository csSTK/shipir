package dev.bethge.shipir.compose.dto;

import dev.bethge.shipir.compose.execution.ComposeOptions;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request and the execution layer must accept exactly the same profile names, because a name the request accepts
 * but {@link ComposeOptions} rejects would fail only at run time.
 */
class ProfilesRequestTest {

    private static final List<String> NAMES = List.of(
            "debug", "_debug", "a", "9lives", "tools.v2", "my-profile_1", "A".repeat(63), "A".repeat(64),
            "-debug", "--privileged", "-", "", "has space", "a/b", "a,b", "ü", "*");

    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void acceptsExactlyTheNamesComposeOptionsAccepts() {
        for (String name : NAMES) {
            boolean accepted = validator.validate(new ProfilesRequest(List.of(name))).isEmpty();
            boolean composeAccepts = ComposeOptions.NAME.matcher(name).matches();
            assertThat(accepted).as("profile '%s'", name).isEqualTo(composeAccepts);
        }
    }

    @Test
    void acceptsUnderscoreFirstAndRejectsDashFirstProfileNames() {
        assertThat(validator.validate(new ProfilesRequest(List.of("_debug")))).isEmpty();
        Set<ConstraintViolation<ProfilesRequest>> violations = validator.validate(new ProfilesRequest(List.of("-debug")));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void sharesOneRegexWithComposeOptions() {
        assertThat(ComposeOptions.NAME.pattern()).isEqualTo(ComposeOptions.NAME_REGEX);
    }
}
