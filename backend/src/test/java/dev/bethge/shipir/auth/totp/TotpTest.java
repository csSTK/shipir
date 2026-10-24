package dev.bethge.shipir.auth.totp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    /** RFC 6238 appendix B, SHA-1 secret "12345678901234567890"; the RFC lists 8 digits, we use the last 6. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private static String at(long seconds) {
        return Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(seconds)));
    }

    @Test
    void matchesRfc6238Vectors() {
        assertThat(at(59)).isEqualTo("287082");
        assertThat(at(1111111109L)).isEqualTo("081804");
        assertThat(at(1111111111L)).isEqualTo("050471");
        assertThat(at(1234567890L)).isEqualTo("005924");
        assertThat(at(2000000000L)).isEqualTo("279037");
        assertThat(at(20000000000L)).isEqualTo("353130");
    }

    @Test
    void base32RoundTripsAndMatchesRfc4648() {
        assertThat(Totp.toBase32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        byte[] secret = Totp.newSecret();
        assertThat(secret).hasSize(20);
        assertThat(Totp.fromBase32(Totp.toBase32(secret))).isEqualTo(secret);
        assertThat(Totp.fromBase32("mzxw 6ytb-oi")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void newSecretsDiffer() {
        assertThat(Totp.newSecret()).isNotEqualTo(Totp.newSecret());
    }

    @Test
    void acceptsOnlyPlusMinusOneStep() {
        long now = 1000;
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now), now, null)).hasValue(now);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now - 1), now, null)).hasValue(now - 1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now + 1), now, null)).hasValue(now + 1);
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now - 2), now, null)).isEqualTo(OptionalLong.empty());
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now + 2), now, null)).isEqualTo(OptionalLong.empty());
    }

    @Test
    void refusesReplayOfAnAlreadyUsedStepOrOlder() {
        long now = 1000;
        String code = Totp.code(RFC_SECRET, now);
        assertThat(Totp.match(RFC_SECRET, code, now, now)).isEqualTo(OptionalLong.empty());
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now - 1), now, now)).isEqualTo(OptionalLong.empty());
        assertThat(Totp.match(RFC_SECRET, Totp.code(RFC_SECRET, now + 1), now, now)).hasValue(now + 1);
    }

    @Test
    void rejectsMalformedCodes() {
        long now = 1000;
        for (String bad : new String[] {"", "12345", "1234567", "abcdef", "12 456", null}) {
            assertThat(Totp.match(RFC_SECRET, bad, now, null)).isEqualTo(OptionalLong.empty());
        }
    }
}
