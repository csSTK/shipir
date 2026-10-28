package dev.bethge.shipir.compose.execution;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComposeOptionsTest {

    @Test
    void acceptsServiceAndProfileNamesStartingWithUnderscore() {
        // Compose allows '_' as first character (service names match ^[a-zA-Z0-9._-]+$); harmless as a flag value.
        assertThat(ComposeOptions.NONE.withServices(List.of("_web", "_worker_1")).services())
                .containsExactly("_web", "_worker_1");
        assertThat(ComposeOptions.ofProfiles(List.of("_debug")).profiles()).containsExactly("_debug");
    }

    @Test
    void rejectsNamesStartingWithDashBecauseTheyWouldReadAsFlags() {
        for (String bad : List.of("-web", "--privileged", "-", "--")) {
            assertThatThrownBy(() -> ComposeOptions.NONE.withServices(List.of(bad))).as("service " + bad)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ComposeOptions.ofProfiles(List.of(bad))).as("profile " + bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
