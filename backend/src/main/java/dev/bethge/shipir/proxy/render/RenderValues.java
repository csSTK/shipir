package dev.bethge.shipir.proxy.render;

import dev.bethge.shipir.proxy.ProxyCertificate;
import dev.bethge.shipir.proxy.ProxyRoute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/** Checks and ordering shared by the renderers. Everything throws {@link IllegalArgumentException} on bad input. */
final class RenderValues {

    private static final Pattern NAME = Pattern.compile("(\\*\\.)?[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?");
    private static final Pattern CONTAINER = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,254}");
    private static final Pattern ENTRYPOINT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,49}");
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9._~/-]{0,254}");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    static final String HEADER_COMMENT = "# Managed by Shipir - changes are overwritten; use the custom file";

    private RenderValues() {
    }

    /** Routes by hostname; the hostname is unique per host, so the order is total. */
    static List<ProxyRoute> sorted(List<ProxyRoute> routes) {
        List<ProxyRoute> sorted = new ArrayList<>(routes);
        sorted.sort(Comparator.comparing(ProxyRoute::getHostname));
        return sorted;
    }

    static String name(String value) {
        return matching(NAME, value, "host name");
    }

    static String container(String value) {
        return matching(CONTAINER, value, "container name");
    }

    static String entrypoint(String value) {
        return matching(ENTRYPOINT, value, "entrypoint");
    }

    static String headerName(String value) {
        return matching(HEADER_NAME, value, "header name");
    }

    /** User name of a basic auth entry: no colon (Traefik's user:hash form) and no quoting needed. */
    static String user(String value) {
        return matching(WORD, value, "user name");
    }

    /** Path prefix without trailing slash, or null when it is the root (nothing to match). */
    static String pathPrefix(String value) {
        if (value == null) {
            return null;
        }
        matching(PATH, value, "path prefix");
        if (value.contains("//") || value.contains("/./") || value.contains("/../") || value.endsWith("/.")
                || value.endsWith("/..")) {
            throw new IllegalArgumentException("Invalid path prefix");
        }
        String trimmed = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Free text (header values, hashes, e-mail): printable ASCII only, so no control character or line separator. */
    static String text(String value, String what) {
        if (value == null) {
            throw new IllegalArgumentException("Missing " + what);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalArgumentException("Control or non-ASCII character in " + what);
            }
        }
        return value;
    }

    /** Sorted copy so output order does not depend on map implementation. */
    static Map<String, String> sorted(Map<String, String> map) {
        return map == null ? Map.of() : new TreeMap<>(map);
    }

    /** Collision-free, readable identifier of a route: lower-case hostname, runs of other characters become one '-'. */
    static String routeId(ProxyRoute route, Set<String> used) {
        String host = route.getHostname();
        String base = (host.startsWith("*.") ? "wildcard." + host.substring(2) : host)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isEmpty()) {
            base = "route";
        }
        String id = base;
        for (int n = 2; !used.add(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    /** The certificate a CUSTOM route points to; every other mode has none. */
    static UUID certificateId(ProxyRoute route, Map<UUID, ProxyCertificate> certificates) {
        UUID id = route.getCertificateId();
        if (id == null || !certificates.containsKey(id)) {
            throw new IllegalArgumentException("Custom certificate of " + route.getHostname() + " is not available");
        }
        return id;
    }

    static Set<String> newIdSet() {
        return new HashSet<>();
    }

    private static String matching(Pattern pattern, String value, String what) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + what);
        }
        return value;
    }
}
