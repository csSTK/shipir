package dev.bethge.shipir.common.util;

import java.util.Locale;

/** Comparison of configured endpoint URLs, so that cosmetic differences do not count as a different endpoint. */
public final class EndpointUrls {

    private EndpointUrls() {
    }

    /** Strip, drop one trailing slash, lower-case scheme and host only (path, port and case of the rest stay). */
    public static String normalize(String url) {
        if (url == null) {
            return "";
        }
        String s = url.strip();
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int schemeEnd = s.indexOf("://");
        if (schemeEnd < 0) {
            return s;
        }
        int hostEnd = s.indexOf('/', schemeEnd + 3);
        if (hostEnd < 0) {
            hostEnd = s.length();
        }
        return s.substring(0, hostEnd).toLowerCase(Locale.ROOT) + s.substring(hostEnd);
    }

    public static boolean sameEndpoint(String a, String b) {
        return normalize(a).equals(normalize(b));
    }
}
