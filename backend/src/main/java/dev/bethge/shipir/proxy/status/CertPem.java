package dev.bethge.shipir.proxy.status;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;

/** Reads the end date of a certificate with the JDK's X.509 support. Only the date leaves; no text of the input. */
final class CertPem {

    static final int MAX_BYTES = 128 * 1024;

    private CertPem() {
    }

    /** notAfter of the first certificate in {@code pem} (PEM or DER); throws {@link StatusParseException} if there is none. */
    static Instant notAfter(byte[] pem) {
        if (pem == null || pem.length == 0 || pem.length > MAX_BYTES) {
            throw new StatusParseException("Certificate could not be read");
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return ((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(pem))).getNotAfter().toInstant();
        } catch (CertificateException | ClassCastException e) {
            throw new StatusParseException("Certificate could not be read");
        }
    }
}
