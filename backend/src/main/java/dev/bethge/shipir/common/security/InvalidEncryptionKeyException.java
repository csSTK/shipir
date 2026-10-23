package dev.bethge.shipir.common.security;

/** Thrown at startup when the configured key is not base64 of exactly 32 bytes. Never contains the key. */
public class InvalidEncryptionKeyException extends RuntimeException {
    public InvalidEncryptionKeyException(String reason) {
        super("Invalid shipir.encryption-key: " + reason + ". Generate one with: openssl rand -base64 32");
    }
}
