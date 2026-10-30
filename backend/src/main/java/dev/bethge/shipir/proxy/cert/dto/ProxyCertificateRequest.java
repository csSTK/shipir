package dev.bethge.shipir.proxy.cert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Upload of a certificate: a name and the PEM chain (leaf first) plus the unencrypted PEM private key. */
public record ProxyCertificateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank String certPem,
        @NotBlank String keyPem) {

    /** The key must never reach a log line through a record's generated toString. */
    @Override
    public String toString() {
        return "ProxyCertificateRequest[name=" + name + "]";
    }
}
