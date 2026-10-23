package dev.bethge.shipir.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code shipir.encryption-key} (env {@code SHIPIR_ENCRYPTION_KEY}): base64 of exactly 32 bytes.
 * Generate one with {@code openssl rand -base64 32}. Null, empty or blank means "not configured".
 */
@ConfigurationProperties("shipir")
public record EncryptionProperties(String encryptionKey) {

    /** Hides the key from logs. */
    @Override
    public String toString() {
        return "EncryptionProperties[encryptionKey=" + (encryptionKey == null || encryptionKey.isBlank() ? "<unset>" : "<hidden>") + "]";
    }
}
