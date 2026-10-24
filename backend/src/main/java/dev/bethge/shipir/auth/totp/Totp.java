package dev.bethge.shipir.auth.totp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP (RFC 6238) with the JDK only: HMAC-SHA1, 6 digits, 30 s step, the parameters every authenticator app
 * supports. A code is accepted for the current step and one step before/after it (clock drift), and only if its
 * step is newer than the last one accepted for that user (replay protection).
 */
public final class Totp {

    public static final int SECRET_BYTES = 20;
    public static final int DIGITS = 6;
    public static final int PERIOD_SECONDS = 30;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static String toBase32(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    /** Accepts lower case and ignores spaces, dashes and padding; anything else is an IllegalArgumentException. */
    public static byte[] fromBase32(String text) {
        String clean = text.replaceAll("[\\s=-]", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8 + 1);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < clean.length(); i++) {
            int value = BASE32.indexOf(clean.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Not a base32 character");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xFF));
                bits -= 8;
                buffer &= (1 << bits) - 1;
            }
        }
        byte[] result = new byte[out.position()];
        out.flip();
        out.get(result);
        return result;
    }

    public static long stepAt(Instant at) {
        return at.getEpochSecond() / PERIOD_SECONDS;
    }

    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 unavailable", e);
        }
    }

    /**
     * Returns the step the code belongs to when it is valid for {@code nowStep - 1 .. nowStep + 1} and the step is
     * greater than {@code lastUsedStep} (null = none used yet); empty otherwise.
     */
    public static OptionalLong match(byte[] secret, String code, long nowStep, Long lastUsedStep) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long found = -1;
        for (long step = nowStep - 1; step <= nowStep + 1; step++) {
            boolean equal = MessageDigest.isEqual(given, code(secret, step).getBytes(StandardCharsets.US_ASCII));
            if (equal && (lastUsedStep == null || step > lastUsedStep)) {
                found = step;
            }
        }
        return found < 0 ? OptionalLong.empty() : OptionalLong.of(found);
    }
}
