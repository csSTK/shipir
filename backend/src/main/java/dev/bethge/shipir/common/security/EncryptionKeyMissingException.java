package dev.bethge.shipir.common.security;

/** Thrown when a secret is encrypted or decrypted while no encryption key is configured. */
public class EncryptionKeyMissingException extends RuntimeException {
    public EncryptionKeyMissingException() {
        super("No encryption key configured; set shipir.encryption-key (SHIPIR_ENCRYPTION_KEY)");
    }
}
