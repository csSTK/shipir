package dev.bethge.shipir.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SecretCipherTest {

    private static final String KEY = keyOf(1);
    private static final String OTHER_KEY = keyOf(2);

    private static String keyOf(long seed) {
        byte[] b = new byte[32];
        new Random(seed).nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private static SecretCipher cipher(String key) {
        return new SecretCipher(new EncryptionProperties(key));
    }

    @Test
    void roundTripsPlaintext() {
        SecretCipher c = cipher(KEY);
        assertThat(c.isConfigured()).isTrue();
        assertThat(c.decrypt(c.encrypt("-----BEGIN PRIVATE KEY----- äöü"))).isEqualTo("-----BEGIN PRIVATE KEY----- äöü");
    }

    @Test
    void outputStartsWithVersionByteAndIv() {
        byte[] out = cipher(KEY).encrypt("x");
        assertThat(out[0]).isEqualTo((byte) 1);
        assertThat(out.length).isEqualTo(1 + 12 + 1 + 16);
    }

    @Test
    void samePlaintextEncryptsToDifferentCiphertexts() {
        SecretCipher c = cipher(KEY);
        assertThat(c.encrypt("secret")).isNotEqualTo(c.encrypt("secret"));
    }

    @Test
    void tamperedCiphertextFailsToDecrypt() {
        SecretCipher c = cipher(KEY);
        byte[] data = c.encrypt("secret");
        data[data.length - 1] ^= 1;
        assertThatThrownBy(() -> c.decrypt(data)).isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    void tamperedIvFailsToDecrypt() {
        SecretCipher c = cipher(KEY);
        byte[] data = c.encrypt("secret");
        data[3] ^= 1;
        assertThatThrownBy(() -> c.decrypt(data)).isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    void unknownVersionOrTruncatedDataFailsToDecrypt() {
        SecretCipher c = cipher(KEY);
        byte[] data = c.encrypt("secret");
        byte[] wrongVersion = data.clone();
        wrongVersion[0] = 9;
        assertThatThrownBy(() -> c.decrypt(wrongVersion)).isInstanceOf(SecretDecryptionException.class);
        assertThatThrownBy(() -> c.decrypt(Arrays.copyOf(data, 5))).isInstanceOf(SecretDecryptionException.class);
        assertThatThrownBy(() -> c.decrypt(null)).isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    void decryptingWithDifferentKeyFails() {
        byte[] data = cipher(KEY).encrypt("secret");
        assertThatThrownBy(() -> cipher(OTHER_KEY).decrypt(data)).isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    void rejectsWrongLengthKeyWithoutEchoingIt() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> cipher(shortKey))
                .isInstanceOf(InvalidEncryptionKeyException.class)
                .hasMessageNotContaining(shortKey);
    }

    @Test
    void rejectsNonBase64KeyWithoutEchoingIt() {
        String bad = "not base64 !!! secret-looking-value";
        assertThatThrownBy(() -> cipher(bad))
                .isInstanceOf(InvalidEncryptionKeyException.class)
                .hasMessageNotContaining(bad)
                .hasMessageNotContaining("secret-looking-value");
    }

    @Test
    void missingEmptyOrBlankKeyIsNotConfigured() {
        for (String value : new String[] {null, "", "   "}) {
            SecretCipher c = cipher(value);
            assertThat(c.isConfigured()).isFalse();
            assertThatThrownBy(() -> c.encrypt("x")).isInstanceOf(EncryptionKeyMissingException.class);
            assertThatThrownBy(() -> c.decrypt(new byte[40])).isInstanceOf(EncryptionKeyMissingException.class);
        }
    }

    @Test
    void missingKeyExceptionDoesNotContainPlaintext() {
        assertThatThrownBy(() -> cipher(null).encrypt("plain-secret-value"))
                .hasMessageNotContaining("plain-secret-value");
    }

    @Test
    void propertiesToStringHidesKey() {
        assertThat(new EncryptionProperties(KEY).toString()).doesNotContain(KEY);
    }

    @Test
    void contextStartsWithEmptyKeyFromComposeDefault() {
        new ApplicationContextRunner().withUserConfiguration(SecretCipher.class)
                .withPropertyValues("shipir.encryption-key=")
                .run(ctx -> assertThat(ctx.getBean(SecretCipher.class).isConfigured()).isFalse());
    }

    @Test
    void contextBindsKeyAndStartupFailsOnInvalidKeyWithoutLeakingIt() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(SecretCipher.class);
        runner.withPropertyValues("shipir.encryption-key=" + KEY)
                .run(ctx -> assertThat(ctx.getBean(SecretCipher.class).isConfigured()).isTrue());
        runner.withPropertyValues("shipir.encryption-key=bogus-key-value")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(InvalidEncryptionKeyException.class);
                });
    }

    @Test
    void neverLogsKeyOrPlaintext(CapturedOutput output) {
        SecretCipher c = cipher(KEY);
        c.decrypt(c.encrypt("plain-secret-value"));
        assertThat(output.getAll()).doesNotContain(KEY).doesNotContain("plain-secret-value");
    }
}
