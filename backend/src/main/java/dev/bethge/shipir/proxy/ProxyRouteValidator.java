package dev.bethge.shipir.proxy;

import dev.bethge.shipir.common.util.DnsNames;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pure validation of route input before it is stored and rendered into proxy config. Everything that ends up in a
 * Traefik file or Caddyfile goes through a strict charset here, so no value can break out of its context.
 * Throws {@link ProxyException} with a stable code on the first violation.
 */
@Component
public class ProxyRouteValidator {

    private static final Pattern CONTAINER = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,254}");
    private static final Pattern ENTRYPOINT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,49}");
    /** Absolute path, no query, no quotes, no percent-escapes (so no encoded dot segments), no empty segments. */
    private static final Pattern PATH_PREFIX = Pattern.compile("/|/[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*/?");
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
    /** Printable ASCII without quotes, backslash, backtick, dollar and braces/angle brackets (template and YAML metacharacters). */
    private static final Pattern HEADER_VALUE = Pattern.compile("[\\x20-\\x7E&&[^\"\\\\`${}<>]]{0,512}");

    /**
     * @param hostId     host the route belongs to
     * @param proxyType  proxy of that host
     * @param in         the input
     * @param takenNames hostnames and aliases of the host's other routes (exclude the route being edited)
     * @param certificate the referenced certificate for CUSTOM, else null
     */
    public void validate(UUID hostId, ProxyType proxyType, RouteInput in, Set<String> takenNames, ProxyCertificate certificate) {
        if (in.certMode() == null) {
            throw bad("INVALID_ROUTE", "Certificate mode is required");
        }
        if (in.backendScheme() == null) {
            throw bad("INVALID_ROUTE", "Backend scheme is required");
        }
        if (proxyType == ProxyType.CADDY && in.certMode() == CertMode.ACME_DNS) {
            throw new ProxyException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FOR_PROXY",
                    "The DNS challenge is only supported with Traefik");
        }
        if (in.entrypoint() != null) {
            if (proxyType == ProxyType.CADDY) {
                throw new ProxyException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FOR_PROXY",
                        "Entrypoints only exist in Traefik");
            }
            if (!ENTRYPOINT.matcher(in.entrypoint()).matches()) {
                throw bad("INVALID_ENTRYPOINT", "Invalid entrypoint name");
            }
        }
        validateNames(in, takenNames, certificate, hostId);
        if (in.targetPort() == null || in.targetPort() < 1 || in.targetPort() > 65535) {
            throw bad("INVALID_PORT", "Port must be 1-65535");
        }
        if (in.containerName() == null || !CONTAINER.matcher(in.containerName()).matches()) {
            throw bad("INVALID_CONTAINER", "Invalid container name");
        }
        validatePath(in.pathPrefix());
        validateBasicAuth(in.basicAuthUsers());
        validateHeaders(in.responseHeaders());
    }

    private void validateNames(RouteInput in, Set<String> takenNames, ProxyCertificate cert, UUID hostId) {
        List<String> aliases = in.aliases() == null ? List.of() : in.aliases();
        List<String> all = new java.util.ArrayList<>();
        all.add(in.hostname());
        all.addAll(aliases);
        Set<String> seen = new HashSet<>();
        for (String name : all) {
            if (name != null && name.length() > 253) {
                throw bad("INVALID_HOSTNAME", "Name is longer than 253 characters");
            }
            boolean wildcard = name != null && name.startsWith("*.");
            String base = wildcard ? name.substring(2) : name;
            if (!DnsNames.isValidHostname(base)) {
                throw bad("INVALID_HOSTNAME", "Not a valid lower-case domain name: " + printable(name));
            }
            if (wildcard && in.certMode() != CertMode.ACME_DNS && in.certMode() != CertMode.CUSTOM) {
                throw bad("WILDCARD_NOT_ALLOWED", "Wildcard names need the DNS challenge or a custom certificate");
            }
            if (!seen.add(name) || takenNames.contains(name)) {
                throw new ProxyException(HttpStatus.CONFLICT, "ROUTE_HOST_TAKEN", "Name already used by a route on this host: " + name);
            }
        }
        if (in.certMode() == CertMode.CUSTOM) {
            if (cert == null) {
                throw bad("CERT_REQUIRED", "A certificate is required for custom certificate mode");
            }
            if (!cert.getHostId().equals(hostId)) {
                throw new ProxyException(HttpStatus.NOT_FOUND, "CERT_NOT_FOUND", "Certificate not found on this host");
            }
            for (String name : all) {
                if (!covers(cert.getDomains(), name)) {
                    throw bad("CERT_DOES_NOT_COVER_HOST", "The certificate does not cover " + name);
                }
            }
        } else if (cert != null) {
            throw bad("INVALID_ROUTE", "A certificate can only be set in custom certificate mode");
        }
    }

    /** Exact match, or a wildcard SAN covering exactly one label below it (as in RFC 6125). */
    private static boolean covers(List<String> certDomains, String name) {
        for (String d : certDomains) {
            if (d.equals(name)) {
                return true;
            }
            if (d.startsWith("*.") && !name.startsWith("*.")) {
                int dot = name.indexOf('.');
                if (dot > 0 && name.substring(dot + 1).equals(d.substring(2))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void validatePath(String prefix) {
        if (prefix == null) {
            return;
        }
        if (prefix.length() > 255 || !PATH_PREFIX.matcher(prefix).matches() || hasDotSegment(prefix)) {
            throw bad("INVALID_PATH_PREFIX", "Path prefix must start with / and use only letters, digits and . _ ~ - /");
        }
    }

    private static boolean hasDotSegment(String path) {
        for (String seg : path.split("/")) {
            if (seg.equals("..") || seg.equals(".")) {
                return true;
            }
        }
        return false;
    }

    private void validateBasicAuth(Map<String, String> users) {
        if (users == null) {
            return;
        }
        for (Map.Entry<String, String> e : users.entrySet()) {
            if (e.getKey() == null || !USER.matcher(e.getKey()).matches()
                    || e.getValue() == null || !BCRYPT.matcher(e.getValue()).matches()) {
                throw bad("INVALID_BASIC_AUTH", "Basic auth users need a plain user name and a bcrypt hash");
            }
        }
    }

    private void validateHeaders(Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() == null || !HEADER_NAME.matcher(e.getKey()).matches()
                    || e.getValue() == null || !HEADER_VALUE.matcher(e.getValue()).matches()) {
                throw bad("INVALID_HEADER", "Invalid response header");
            }
        }
    }

    private static ProxyException bad(String code, String message) {
        return new ProxyException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** Keeps control characters out of error messages. */
    private static String printable(String s) {
        return s == null ? "null" : s.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
