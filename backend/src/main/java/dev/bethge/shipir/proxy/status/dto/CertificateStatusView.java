package dev.bethge.shipir.proxy.status.dto;

import java.time.Instant;

/** Certificate state of one route hostname. {@code lastError} is a short sanitized log line, only set for FAILED. No key material, ever. */
public record CertificateStatusView(String hostname, CertificateStatus status, Instant notAfter, String lastError) {
}
