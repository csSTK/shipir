package dev.bethge.shipir.compose;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Daily UTC window: start inclusive, end exclusive, start after end wraps over midnight, no window = always open. */
class ImageUpdateWindowTest {

    private static Instant at(String time) {
        return Instant.parse("2026-03-10T" + time + ":00Z");
    }

    @Test
    void noWindowIsAlwaysOpen() {
        assertThat(ImageUpdateWindow.parse(null, null)).isNull();
        assertThat(ImageUpdateWindow.open(null, at("12:00"))).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"01:59,false", "02:00,true", "03:30,true", "04:59,true", "05:00,false", "12:00,false"})
    void sameDayWindowIncludesStartAndExcludesEnd(String time, boolean expected) {
        ImageUpdateWindow w = ImageUpdateWindow.parse("02:00", "05:00");
        assertThat(ImageUpdateWindow.open(w, at(time))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"21:59,false", "22:00,true", "23:59,true", "00:00,true", "05:59,true", "06:00,false", "12:00,false"})
    void windowOverMidnightWraps(String time, boolean expected) {
        ImageUpdateWindow w = ImageUpdateWindow.parse("22:00", "06:00");
        assertThat(ImageUpdateWindow.open(w, at(time))).isEqualTo(expected);
    }

    @Test
    void windowIsEvaluatedInUtcNotInTheDefaultZone() {
        ImageUpdateWindow w = ImageUpdateWindow.parse("02:00", "05:00");
        assertThat(ImageUpdateWindow.open(w, Instant.parse("2026-07-01T03:00:00Z"))).isTrue();
        assertThat(ImageUpdateWindow.open(w, Instant.parse("2026-07-01T03:00:00+05:00"))).isFalse();
    }

    @ParameterizedTest
    @CsvSource(value = {"02:00,NULL", "NULL,05:00", "03:00,03:00", "25:00,05:00", "2:00,05:00", "02:00,05:61", "abc,05:00",
            "02:00:30,05:00"}, nullValues = "NULL")
    void invalidCombinationsAreRejected(String start, String end) {
        assertThatThrownBy(() -> ImageUpdateWindow.parse(start, end))
                .isInstanceOf(DeploymentException.class)
                .satisfies(e -> assertThat(((DeploymentException) e).code()).isEqualTo("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void blankBothMeansNoWindow(String blank) {
        assertThat(ImageUpdateWindow.parse(blank, blank)).isNull();
    }
}
