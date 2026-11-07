package dev.bethge.shipir.config_editor;

import java.util.Locale;

/**
 * Single place that decides which deployment files hold secrets: {@code .env*}, {@code *.env}, anything with
 * {@code secret} in a path segment, {@code *.pem}, {@code *.key}, {@code *.p12}, {@code *.pfx}, {@code *.jks},
 * {@code *.keystore}, {@code *.kdbx}, {@code *.p8}, {@code *.ppk}, every name starting with {@code id_}
 * (SSH private keys and their {@code _sk}/{@code .bak} variants; public {@code .pub} keys stay readable), names starting with
 * {@code .netrc} or {@code .pgpass} (incl. backups) and names starting with {@code credentials}. Matching is case-insensitive, ignores trailing dots and spaces of a segment and looks at every
 * segment of the path relative to the deployment directory (so {@code repo/.env} and {@code secrets/db.conf} match).
 * Their content is only readable with OPERATE access, never by view-only users and never through the AI assistant.
 */
public final class EnvLikeFiles {

    private EnvLikeFiles() {
    }

    /** True when the relative path (any separator style) points to or into an env-like file. */
    public static boolean matches(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        for (String segment : relativePath.replace('\\', '/').split("/")) {
            String s = stripTrailingDotsAndSpaces(segment.toLowerCase(Locale.ROOT));
            if (s.startsWith(".env") || s.endsWith(".env") || s.contains("secret") || s.endsWith(".pem") || s.endsWith(".key")
                    || s.endsWith(".p12") || s.endsWith(".pfx") || s.endsWith(".p8") || s.endsWith(".ppk")
                    || s.startsWith("credentials") || s.endsWith(".jks") || s.endsWith(".keystore") || s.endsWith(".kdbx")
                    || s.startsWith(".netrc") || s.startsWith(".pgpass") || (s.startsWith("id_") && !s.endsWith(".pub"))) {
                return true;
            }
        }
        return false;
    }

    /** Windows ignores trailing dots and spaces in file names ({@code id_rsa.} is {@code id_rsa}), so they must not hide a match. */
    private static String stripTrailingDotsAndSpaces(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) {
            end--;
        }
        return s.substring(0, end);
    }
}
