package dev.bethge.shipir.streaming;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogStreamPropertiesTest {

    @Test
    void negativeSafetyMarginIsRejected() {
        assertThatThrownBy(() -> new LogStreamProperties(200, 500, 8192, 1000, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroMarginIsAllowedAndMissingMarginDefaultsToFiveSeconds() {
        assertThat(new LogStreamProperties(200, 500, 8192, 1000, Duration.ZERO).sinceSafetyMargin()).isZero();
        assertThat(new LogStreamProperties(200, 500, 8192, 1000, null).sinceSafetyMargin())
                .isEqualTo(Duration.ofSeconds(5));
    }
}
