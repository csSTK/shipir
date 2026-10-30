package dev.bethge.shipir.common.util;

import java.util.regex.Pattern;

/** Shared check for the public DNS names Shipir writes into proxy configuration (compose domains, proxy routes). */
public final class DnsNames {

    /** Lower-case DNS name with at least two labels (no localhost, no IP-less single labels), max 253 chars. Punycode (xn--) is fine, raw unicode is not. */
    private static final Pattern HOSTNAME = Pattern.compile(
            "(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");

    private DnsNames() {
    }

    public static boolean isValidHostname(String name) {
        return name != null && HOSTNAME.matcher(name).matches();
    }
}
