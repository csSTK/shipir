package dev.bethge.shipir.proxy.cert;

import java.time.Instant;
import java.util.List;

/** Validated result of {@link CertificateParser}: canonical PEMs plus what was read from the leaf certificate. */
record CertificateMaterial(String certPem, String keyPem, List<String> domains, Instant notAfter) {

    @Override
    public String toString() {
        return "CertificateMaterial[domains=" + domains + ", notAfter=" + notAfter + "]";
    }
}
