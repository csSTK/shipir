package dev.bethge.shipir.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DnsNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"app.example.com", "a.b", "xn--bcher-kva.example.com", "my-app.example.co.uk", "1.example.com"})
    void acceptsLowerCaseDomainNames(String name) {
        assertThat(DnsNames.isValidHostname(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "localhost", "App.example.com", "app.example.com.", ".example.com", "a..b",
            "-a.example.com", "a-.example.com", "bücher.example.com", "app_x.example.com", "app.example.com/x",
            "app.example.com\n", "*.example.com", "app example.com"})
    void rejectsEverythingElse(String name) {
        assertThat(DnsNames.isValidHostname(name)).isFalse();
    }

    @Test
    void rejectsNullAndTooLongNames() {
        assertThat(DnsNames.isValidHostname(null)).isFalse();
        assertThat(DnsNames.isValidHostname(("a".repeat(60) + ".").repeat(5) + "com")).isFalse();
        assertThat(DnsNames.isValidHostname("a".repeat(64) + ".com")).isFalse();
    }
}
