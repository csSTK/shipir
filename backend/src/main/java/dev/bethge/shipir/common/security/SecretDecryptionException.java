package dev.bethge.shipir.common.security;

/** Thrown when a stored secret cannot be decrypted (wrong key, tampered or malformed data). */
public class SecretDecryptionException extends RuntimeException {
    public SecretDecryptionException() {
        super("Secret could not be decrypted (wrong key or corrupted data)");
    }
}
