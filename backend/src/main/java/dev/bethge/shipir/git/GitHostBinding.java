package dev.bethge.shipir.git;

import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Binds a Git credential to one host (optionally with a port). The token is only ever sent to a repository URL whose host
 * equals the bound one exactly: case-insensitive, one trailing dot ignored, no subdomain or suffix matching. The host is
 * taken from the parsed URL (never from a substring), so {@code https://github.com@evil.example} counts as evil.example.
 */
final class GitHostBinding {

    private static final Pattern HOST = Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*(?::([0-9]{1,5}))?");

    private GitHostBinding() {
    }

    /** Canonical form (lower case, no trailing dot) of {@code host} or {@code host:port}; 400 INVALID_CREDENTIAL_HOST otherwise. */
    static String normalize(String input) {
        String s = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        String hostPart = colon < 0 ? s : s.substring(0, colon);
        if (hostPart.endsWith(".")) {
            hostPart = hostPart.substring(0, hostPart.length() - 1);
        }
        String canonical = colon < 0 ? hostPart : hostPart + s.substring(colon);
        var m = HOST.matcher(canonical);
        if (hostPart.isEmpty() || !m.matches() || (m.group(1) != null && !validPort(m.group(1)))) {
            throw new GitException(HttpStatus.BAD_REQUEST, "INVALID_CREDENTIAL_HOST",
                    "The host must be a plain host name such as github.com, optionally with a port");
        }
        return canonical;
    }

    /** True if the credential's bound host (null or blank = unbound) matches the host of {@code url}. */
    static boolean matches(String boundHost, URI url) {
        return url != null && matches(boundHost, url.getHost(), url.getPort(), url.getScheme());
    }

    /** Same check on already parsed parts; {@code port < 0} means the scheme's default port. */
    static boolean matches(String boundHost, String urlHost, int urlPort, String scheme) {
        if (boundHost == null || boundHost.isBlank() || urlHost == null) {
            return false;
        }
        String host = stripDot(urlHost.toLowerCase(Locale.ROOT));
        int colon = boundHost.indexOf(':');
        String boundName = colon < 0 ? boundHost : boundHost.substring(0, colon);
        if (!stripDot(boundName.toLowerCase(Locale.ROOT)).equals(host)) {
            return false;
        }
        return colon < 0 || Integer.parseInt(boundHost.substring(colon + 1)) == effectivePort(urlPort, scheme);
    }

    private static int effectivePort(int port, String scheme) {
        if (port >= 0) {
            return port;
        }
        return "http".equalsIgnoreCase(scheme) ? 80 : 443;
    }

    private static boolean validPort(String port) {
        int p = Integer.parseInt(port);
        return p >= 1 && p <= 65535;
    }

    private static String stripDot(String host) {
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }
}
