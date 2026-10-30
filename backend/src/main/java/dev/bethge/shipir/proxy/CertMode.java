package dev.bethge.shipir.proxy;

/** How a route (or the proxy default) obtains its TLS certificate. Names match the V40 CHECK constraints. */
public enum CertMode {
    ACME_HTTP,
    /** DNS-01 challenge; Traefik only, needed for wildcard names. */
    ACME_DNS,
    /** The proxy's own CA (self-signed); no public trust. */
    INTERNAL,
    /** An uploaded {@link ProxyCertificate}. */
    CUSTOM
}
