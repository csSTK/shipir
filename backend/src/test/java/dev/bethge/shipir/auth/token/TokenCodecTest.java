package dev.bethge.shipir.auth.token;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCodecTest {

    @Test
    void tokenHasPrefixAnd32RandomBytesUrlSafeBase64() {
        String token = TokenCodec.newToken("shp_");

        assertThat(token).startsWith("shp_");
        assertThat(token).hasSize(4 + 43);
        assertThat(token.substring(4)).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void tokensAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(TokenCodec.newToken("shp_"));
        }
        assertThat(seen).hasSize(200);
    }

    @Test
    void sha256HexIsLowercaseSha256OfTheUtf8Token() throws Exception {
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("shp_abc".getBytes(StandardCharsets.UTF_8)));

        assertThat(TokenCodec.sha256Hex("shp_abc")).isEqualTo(expected).matches("[0-9a-f]{64}");
    }

    @Test
    void hashComparisonMatchesEqualAndRejectsEverythingElse() {
        String hash = TokenCodec.sha256Hex("shp_abc");

        assertThat(TokenCodec.hashesEqual(hash, TokenCodec.sha256Hex("shp_abc"))).isTrue();
        assertThat(TokenCodec.hashesEqual(hash, TokenCodec.sha256Hex("shp_abd"))).isFalse();
        assertThat(TokenCodec.hashesEqual(hash, hash.substring(1))).isFalse();
        assertThat(TokenCodec.hashesEqual(hash, "")).isFalse();
        assertThat(TokenCodec.hashesEqual(null, hash)).isFalse();
        assertThat(TokenCodec.hashesEqual(hash, null)).isFalse();
    }

    @Test
    void displayPrefixIsTheSchemePlusFourSecretCharsOnly() {
        String token = TokenCodec.newToken("shp_");

        assertThat(TokenCodec.displayPrefix(token)).isEqualTo(token.substring(0, 8));
    }
}
