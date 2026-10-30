package dev.bethge.shipir.proxy.status;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Which issued certificate answers for a hostname: exact name, or a wildcard that covers exactly one label. */
final class CertificateMatcher {

    private CertificateMatcher() {
    }

    static boolean covers(String certDomain, String host) {
        if (certDomain == null || host == null) {
            return false;
        }
        String cert = certDomain.toLowerCase(Locale.ROOT);
        String name = host.toLowerCase(Locale.ROOT);
        if (cert.equals(name)) {
            return true;
        }
        if (!cert.startsWith("*.") || name.startsWith("*.")) {
            return false;
        }
        String suffix = cert.substring(1); // ".example.com"
        if (!name.endsWith(suffix) || name.length() <= suffix.length()) {
            return false;
        }
        return name.indexOf('.') == name.length() - suffix.length();
    }

    /** The covering certificate that ends last (valid or not). */
    static Optional<IssuedCert> best(List<IssuedCert> certs, String host) {
        return certs.stream()
                .filter(c -> c.domains().stream().anyMatch(d -> covers(d, host)))
                .max(Comparator.comparing(IssuedCert::notAfter));
    }
}
