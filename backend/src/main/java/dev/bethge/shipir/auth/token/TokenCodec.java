package dev.bethge.shipir.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation and hashing of bearer secrets (API tokens, webhook tokens). A secret is a scheme prefix plus 32 random
 * bytes as URL-safe Base64 without padding. Only the SHA-256 hex digest is ever stored: the secrets carry 256 bits of
 * entropy, so a fast hash is enough (no salt or stretching needed) and it allows an indexed lookup by hash.
 */
public final class TokenCodec {

    /** Length of the random part: 32 bytes in Base64url without padding. */
    public static final int RANDOM_PART_LENGTH = 43;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DISPLAY_SECRET_CHARS = 4;

    private TokenCodec() {
    }

    public static String newToken(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes. */
    public static String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    /** Constant-time comparison of two hex digests; false for null or different lengths. */
    public static boolean hashesEqual(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** The non-secret label shown in lists: scheme prefix plus the first four characters of the secret part. */
    public static String displayPrefix(String token) {
        int schemeEnd = token.indexOf('_') + 1;
        return token.substring(0, schemeEnd + DISPLAY_SECRET_CHARS);
    }
}
