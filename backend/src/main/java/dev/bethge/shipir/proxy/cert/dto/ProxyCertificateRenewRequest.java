package dev.bethge.shipir.proxy.cert.dto;

import jakarta.validation.constraints.NotBlank;

/** Replacement certificate chain and private key of an existing certificate. */
public record ProxyCertificateRenewRequest(@NotBlank String certPem, @NotBlank String keyPem) {

    @Override
    public String toString() {
        return "ProxyCertificateRenewRequest[]";
    }
}
