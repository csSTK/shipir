package dev.bethge.shipir.common.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Encrypts secrets at rest (e.g. TLS material of Docker hosts) with AES-256-GCM.
 * Output layout: version byte (1) + 12-byte random IV + ciphertext incl. 128-bit tag.
 * The key is optional; without it {@link #isConfigured()} is false and encrypt/decrypt throw
 * {@link EncryptionKeyMissingException}. Key and plaintext are never logged or put in messages.
 * Generate a key with {@code openssl rand -base64 32}.
 */
@Component
@EnableConfigurationProperties(EncryptionProperties.class)
public class SecretCipher {

    private static final byte VERSION = 1;
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public SecretCipher(EncryptionProperties properties) {
        this.key = parse(properties.encryptionKey());
    }

    private static SecretKeySpec parse(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidEncryptionKeyException("not valid base64");
        }
        if (bytes.length != KEY_BYTES) {
            Arrays.fill(bytes, (byte) 0);
            throw new InvalidEncryptionKeyException("must decode to exactly " + KEY_BYTES + " bytes");
        }
        SecretKeySpec spec = new SecretKeySpec(bytes, "AES");
        Arrays.fill(bytes, (byte) 0);
        return spec;
    }

    public boolean isConfigured() {
        return key != null;
    }

    public byte[] encrypt(String plaintext) {
        requireKey();
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + IV_BYTES + ciphertext.length];
            out[0] = VERSION;
            System.arraycopy(iv, 0, out, 1, IV_BYTES);
            System.arraycopy(ciphertext, 0, out, 1 + IV_BYTES, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed");
        }
    }

    public String decrypt(byte[] data) {
        requireKey();
        if (data == null || data.length < 1 + IV_BYTES + TAG_BITS / 8 || data[0] != VERSION) {
            throw new SecretDecryptionException();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 1, IV_BYTES));
            byte[] plain = cipher.doFinal(data, 1 + IV_BYTES, data.length - 1 - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new SecretDecryptionException();
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new EncryptionKeyMissingException();
        }
    }
}
