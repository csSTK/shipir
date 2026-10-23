package dev.bethge.shipir.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderConfigTest {

    private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();

    @Test
    void encodesAndMatches128CharPasswordOver72Bytes() {
        String password = ("äöü€".repeat(32)).substring(0, 128); // 128 chars, 4 chars = 11 bytes
        assertThat(password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(72);

        String hash = encoder.encode(password);

        assertThat(hash).startsWith("{argon2}");
        assertThat(encoder.matches(password, hash)).isTrue();
        assertThat(encoder.matches(password + "!", hash)).isFalse();
    }
}
