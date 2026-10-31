package dev.bethge.shipir.config_editor;

import java.util.Locale;

/**
 * Single place that decides which deployment files hold secrets: {@code .env*}, {@code *.env}, anything with
 * {@code secret} in a path segment, {@code *.pem}, {@code *.key}, {@code *.p12}, {@code *.pfx}, {@code *.jks},
 * {@code *.keystore}, {@code *.kdbx}, the exact names {@code id_rsa}, {@code id_ed25519}, {@code id_ecdsa},
 * {@code id_dsa}, {@code .netrc}, {@code .pgpass} (public {@code .pub} keys stay readable) and names starting with {@code credentials}. Matching is case-insensitive and looks at every
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
            String s = segment.toLowerCase(Locale.ROOT);
            if (s.startsWith(".env") || s.endsWith(".env") || s.contains("secret") || s.endsWith(".pem") || s.endsWith(".key")
                    || s.endsWith(".p12") || s.endsWith(".pfx") || s.equals("id_rsa") || s.startsWith("credentials")
                    || s.equals("id_ed25519") || s.equals("id_ecdsa") || s.equals("id_dsa") || s.endsWith(".jks")
                    || s.endsWith(".keystore") || s.endsWith(".kdbx") || s.equals(".netrc") || s.equals(".pgpass")) {
                return true;
            }
        }
        return false;
    }
}
