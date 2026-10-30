package dev.bethge.shipir.proxy.status.dto;

/** ISSUED: the proxy holds a valid certificate. PENDING: none yet (or expired). FAILED: PENDING plus a recent error in the proxy log. */
public enum CertificateStatus {
    ISSUED,
    PENDING,
    FAILED,
    INTERNAL,
    CUSTOM
}
